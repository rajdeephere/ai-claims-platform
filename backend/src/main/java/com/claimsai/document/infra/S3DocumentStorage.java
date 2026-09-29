package com.claimsai.document.infra;

import com.claimsai.document.app.StorageProperties;
import com.claimsai.document.domain.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * S3 API adapter: SeaweedFS locally and in tests, Supabase Storage in the cloud (ADR-0019).
 * <ul>
 *   <li>path-style URLs ({@code endpoint/bucket/key}): what non-AWS stores expect</li>
 *   <li>checksums only when required: SDK 2.30+ otherwise adds CRC headers some S3-compatible stores and
 *       browser uploads can't satisfy</li>
 * </ul>
 */
@Component
public class S3DocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    private final StorageProperties properties;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3DocumentStorage(StorageProperties properties) {
        this.properties = properties;
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        this.s3 = S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(properties.browserEndpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        if (properties.createBucket()) {
            ensureBucket();
        }
    }

    private void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(properties.bucket()));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(properties.bucket()));
            log.info("Created bucket {}", properties.bucket());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            s3.createBucket(b -> b.bucket(properties.bucket()));
            log.info("Created bucket {}", properties.bucket());
        }
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .build();
        PresignedPutObjectRequest signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build());
        Map<String, String> headers = new LinkedHashMap<>();
        signed.signedHeaders().forEach((name, values) -> {
            // the browser sets Host and Content-Length itself (and refuses to let scripts set them)
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUpload(URI.create(signed.url().toString()), signed.httpRequest().method().name(),
                headers, signed.expiration());
    }

    @Override
    public URI presignDownload(String key, String fileName, String contentType, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                // attachment: the browser saves the file instead of rendering it on our origin
                .responseContentDisposition(contentDisposition(fileName))
                .responseContentType(contentType)
                .build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl).getObjectRequest(get).build()).url().toString());
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        try {
            HeadObjectResponse head = s3.headObject(b -> b.bucket(properties.bucket()).key(key));
            return Optional.of(new StoredObject(head.contentLength()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public InputStream open(String key) {
        return s3.getObject(b -> b.bucket(properties.bucket()).key(key));
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(properties.bucket()).key(key));
    }

    /** RFC 6266: an ASCII fallback plus the UTF-8 name for browsers that support it. */
    static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        String utf8 = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8;
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }
}
