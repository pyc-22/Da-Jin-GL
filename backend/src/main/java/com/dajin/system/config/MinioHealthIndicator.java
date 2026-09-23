package com.dajin.system.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Verifies the configured object store and bucket, not just the HTTP process. */
@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {
    private final MinioClient client;
    private final String bucket;

    public MinioHealthIndicator(@Value("${minio.endpoint}") String endpoint,
                                @Value("${minio.access-key}") String accessKey,
                                @Value("${minio.secret-key}") String secretKey,
                                @Value("${minio.bucket}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) return Health.down().withDetail("bucket", bucket).withDetail("reason", "bucket_not_found").build();
            return Health.up().withDetail("bucket", bucket).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("bucket", bucket).build();
        }
    }
}
