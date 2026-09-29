package com.claimsai.document.infra;

import com.claimsai.document.domain.ContentInspector;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One pass over the file: Tika detects the type from the first bytes ("magic numbers", no file name, so an
 * .exe renamed to .pdf is still an exe), then the rest streams through a SHA-256 digest. Constant memory
 * whatever the file size.
 */
@Component
public class TikaContentInspector implements ContentInspector {

    private final Tika tika = new Tika();

    @Override
    public Inspection inspect(InputStream content) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (DigestInputStream digesting = new DigestInputStream(content, sha256);
             BufferedInputStream buffered = new BufferedInputStream(digesting, 64 * 1024)) {
            // detect() marks the stream, reads the first bytes and resets: nothing is lost for the hash
            String type = tika.detect(buffered);
            long size = buffered.transferTo(OutputStream.nullOutputStream());
            return new Inspection(type, size, HexFormat.of().formatHex(sha256.digest()));
        }
    }
}
