package com.ktb.chatapp.service;

import com.ktb.chatapp.config.S3StorageProperties;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.PresignedUploadRequest;
import com.ktb.chatapp.dto.PresignedUploadResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.FileStatus;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.util.FileUtil;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3DirectUploadService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3StorageProperties properties;
    private final FileRepository fileRepository;
    private final AsyncFileVariantService asyncFileVariantService;

    public PresignedUploadResponse createUpload(PresignedUploadRequest request, String uploaderId) {
        FileUtil.validateUploadMetadata(request.originalFilename(), request.contentType(), request.size());

        String originalFilename = StringUtils.cleanPath(request.originalFilename());
        String safeFileName = FileUtil.generateSafeFileName(originalFilename);
        String key = StorageKey.chat(safeFileName);

        File file = File.builder()
                .filename(safeFileName)
                .originalname(FileUtil.normalizeOriginalFilename(originalFilename))
                .mimetype(request.contentType())
                .size(request.size())
                .path(key)
                .status(FileStatus.UPLOAD_PENDING)
                .user(uploaderId)
                .uploadDate(LocalDateTime.now())
                .build();

        File savedFile = fileRepository.save(file);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(request.contentType())
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(properties.presignTtl())
                .putObjectRequest(putObjectRequest)
                .build();

        var presigned = s3Presigner.presignPutObject(presignRequest);
        Map<String, String> headers = Map.of("Content-Type", request.contentType());

        return new PresignedUploadResponse(
                savedFile.getId(),
                safeFileName,
                key,
                presigned.url().toString(),
                "PUT",
                headers,
                properties.presignTtl().toSeconds(),
                FileResponse.from(savedFile));
    }

    public FileResponse completeUpload(String fileId, String uploaderId) {
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("파일을 찾을 수 없습니다."));

        if (!uploaderId.equals(file.getUser())) {
            throw new IllegalArgumentException("파일에 접근할 권한이 없습니다.");
        }

        if (file.getStatus() == FileStatus.READY || file.getStatus() == FileStatus.PROCESSING) {
            return FileResponse.from(file);
        }

        var headObject = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(properties.bucket())
                .key(file.getPath())
                .build());

        if (headObject.contentLength() != file.getSize()) {
            file.setStatus(FileStatus.FAILED);
            fileRepository.save(file);
            throw new IllegalArgumentException("업로드된 파일 크기가 요청 정보와 일치하지 않습니다.");
        }

        String uploadedContentType = headObject.contentType();
        if (StringUtils.hasText(uploadedContentType) && !file.getMimetype().equals(uploadedContentType)) {
            file.setStatus(FileStatus.FAILED);
            fileRepository.save(file);
            throw new IllegalArgumentException("업로드된 파일 형식이 요청 정보와 일치하지 않습니다.");
        }

        file.setStatus(FileStatus.PROCESSING);
        File savedFile = fileRepository.save(file);
        asyncFileVariantService.generateChatVariants(savedFile.getId());

        return FileResponse.from(savedFile);
    }
}
