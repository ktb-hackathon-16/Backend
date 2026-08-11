package com.ktb.chatapp.storage;

import com.ktb.chatapp.config.S3StorageProperties;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Storage implements StoragePort {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3StorageProperties properties;

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .cacheControl(cacheControlFor(key));

        if (StringUtils.hasText(contentType)) {
            request.contentType(contentType);
        }

        s3Client.putObject(request.build(), RequestBody.fromInputStream(content, size));
        return new StoredObject(key, size);
    }

    @Override
    public Optional<Resource> open(String key) {
        return Optional.empty();
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build());
    }

    @Override
    public Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
        GetObjectRequest.Builder get = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key);

        if (disposition != null) {
            get.responseContentDisposition(disposition.toString());
        }

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl == null ? properties.presignTtl() : ttl)
                .getObjectRequest(get.build())
                .build();

        return Optional.of(URI.create(s3Presigner.presignGetObject(presignRequest).url().toString()));
    }

    private String cacheControlFor(String key) {
        if (key != null && key.startsWith("media/")) {
            return properties.cacheControlPublic();
        }
        return properties.cacheControlPrivate();
    }
}
