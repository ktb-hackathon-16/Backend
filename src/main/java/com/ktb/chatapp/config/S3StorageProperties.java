package com.ktb.chatapp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage.s3")
public record S3StorageProperties(
        String bucket,
        String region,
        Duration presignTtl,
        String cacheControlPublic,
        String cacheControlPrivate
) {
}
