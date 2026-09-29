package com.claimsai.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The real backing services, in containers: PostgreSQL 16 (same major version as docker-compose and
 * Supabase) and SeaweedFS as the S3-compatible store (same image as docker-compose).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String S3_ACCESS_KEY = "aiclaims";
    public static final String S3_SECRET_KEY = "aiclaims-local-secret";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    GenericContainer<?> seaweedfs() {
        return new GenericContainer<>("chrislusf/seaweedfs:4.48")
                .withCopyFileToContainer(MountableFile.forClasspathResource("seaweedfs/s3.json"), "/etc/seaweedfs/s3.json")
                .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
                .withExposedPorts(8333)
                // anonymous requests get 403: the S3 API is up and enforcing auth
                .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403));
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(GenericContainer<?> seaweedfs) {
        return registry -> {
            registry.add("app.storage.endpoint",
                    () -> "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333));
            registry.add("app.storage.access-key", () -> S3_ACCESS_KEY);
            registry.add("app.storage.secret-key", () -> S3_SECRET_KEY);
            registry.add("app.storage.create-bucket", () -> "true");
        };
    }
}
