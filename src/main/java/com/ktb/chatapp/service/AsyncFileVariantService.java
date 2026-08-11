package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.FileStatus;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.storage.StoragePort;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncFileVariantService {

    private final FileRepository fileRepository;
    private final StoragePort storagePort;
    private final ImageVariantService imageVariantService;

    @Async("fileVariantExecutor")
    public void generateChatVariants(String fileId) {
        File file = fileRepository.findById(fileId).orElse(null);
        if (file == null) {
            log.warn("variant 생성 대상 파일을 찾을 수 없습니다: fileId={}", fileId);
            return;
        }

        if (!isImage(file.getMimetype())) {
            markReady(file);
            return;
        }

        try {
            var resource = storagePort.open(file.getPath())
                    .orElseThrow(() -> new IllegalStateException("원본 파일을 스토리지에서 찾을 수 없습니다."));

            try (InputStream inputStream = resource.getInputStream()) {
                imageVariantService.storeChatVariants(inputStream, file.getMimetype(), file.getFilename())
                        .ifPresent(variants -> {
                            file.setPreviewPath(variants.previewPath());
                            file.setPreviewSize(variants.previewSize());
                            file.setThumbnailPath(variants.thumbnailPath());
                            file.setThumbnailSize(variants.thumbnailSize());
                        });
            }

            markReady(file);
            log.info("chat image variants generated: fileId={}, key={}", file.getId(), file.getPath());
        } catch (Exception e) {
            file.setStatus(FileStatus.FAILED);
            fileRepository.save(file);
            log.error("chat image variant generation failed: fileId={}, key={}", file.getId(), file.getPath(), e);
        }
    }

    private void markReady(File file) {
        file.setStatus(FileStatus.READY);
        fileRepository.save(file);
    }

    private boolean isImage(String contentType) {
        return contentType != null && contentType.startsWith("image/");
    }
}
