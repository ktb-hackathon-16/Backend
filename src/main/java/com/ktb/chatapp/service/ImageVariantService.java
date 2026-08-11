package com.ktb.chatapp.service;

import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.util.FileUtil;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageVariantService {

    private static final String IMAGE_JPEG = "image/jpeg";
    private static final List<Integer> PROFILE_SIZES = List.of(64, 128, 256);

    private final StoragePort storagePort;

    public String storeProfileAvatar(MultipartFile file, String userId) {
        String token = UUID.randomUUID().toString();
        try {
            BufferedImage source = readImage(file);
            for (Integer size : PROFILE_SIZES) {
                byte[] bytes = encodeJpeg(resizeSquare(source, size));
                String key = StorageKey.mediaProfile(userId, token + "-avatar-" + size + ".jpg");
                storagePort.put(new ByteArrayInputStream(bytes), key, IMAGE_JPEG, bytes.length);
            }
            return StorageKey.mediaProfile(userId, token + "-avatar-128.jpg");
        } catch (IOException | IllegalArgumentException e) {
            log.warn("프로필 이미지 리사이징 실패, 원본 저장으로 폴백: {}", e.getMessage());
            return storeProfileOriginal(file, userId, token);
        }
    }

    public Optional<ChatImageVariants> storeChatVariants(MultipartFile file, String safeFileName) {
        if (!isImage(file)) {
            return Optional.empty();
        }

        try (InputStream inputStream = file.getInputStream()) {
            return storeChatVariants(inputStream, file.getContentType(), safeFileName);
        } catch (IOException e) {
            log.warn("채팅 이미지 variant 생성 실패, 원본만 저장: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ChatImageVariants> storeChatVariants(InputStream inputStream, String contentType, String safeFileName) {
        if (!isImage(contentType)) {
            return Optional.empty();
        }

        try {
            BufferedImage source = readImage(inputStream);
            String baseName = stripExtension(safeFileName);
            byte[] preview = encodeJpeg(resizeToMaxWidth(source, 1280));
            byte[] thumbnail = encodeJpeg(resizeToMaxWidth(source, 320));
            String previewKey = StorageKey.mediaChatPreview(baseName + "-preview.jpg");
            String thumbnailKey = StorageKey.mediaChatThumbnail(baseName + "-thumb.jpg");

            storagePort.put(new ByteArrayInputStream(preview), previewKey, IMAGE_JPEG, preview.length);
            storagePort.put(new ByteArrayInputStream(thumbnail), thumbnailKey, IMAGE_JPEG, thumbnail.length);

            return Optional.of(new ChatImageVariants(previewKey, preview.length, thumbnailKey, thumbnail.length));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("채팅 이미지 variant 생성 실패, 원본만 저장: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void deleteProfileImageSet(String profileImageKey) {
        if (!StringUtils.hasText(profileImageKey)) {
            return;
        }
        if (!profileImageKey.startsWith("media/profiles/") || !profileImageKey.endsWith("-avatar-128.jpg")) {
            storagePort.delete(profileImageKey);
            return;
        }

        String base = profileImageKey.substring(0, profileImageKey.length() - "-avatar-128.jpg".length());
        for (Integer size : PROFILE_SIZES) {
            deleteQuietly(base + "-avatar-" + size + ".jpg");
        }
    }

    private String storeProfileOriginal(MultipartFile file, String userId, String token) {
        try {
            String extension = FileUtil.getFileExtension(file.getOriginalFilename()).toLowerCase();
            String key = StorageKey.mediaProfile(userId, token + "-original." + extension);
            storagePort.put(file.getInputStream(), key, file.getContentType(), file.getSize());
            return key;
        } catch (IOException ex) {
            throw new RuntimeException("프로필 이미지 저장에 실패했습니다: " + ex.getMessage(), ex);
        }
    }

    private BufferedImage readImage(MultipartFile file) throws IOException {
        return readImage(file.getInputStream());
    }

    private BufferedImage readImage(InputStream inputStream) throws IOException {
        BufferedImage image = ImageIO.read(inputStream);
        if (image == null) {
            throw new IllegalArgumentException("이미지 데이터를 읽을 수 없습니다.");
        }
        return image;
    }

    private boolean isImage(MultipartFile file) {
        return isImage(file.getContentType());
    }

    private boolean isImage(String contentType) {
        return contentType != null && contentType.startsWith("image/");
    }

    private BufferedImage resizeSquare(BufferedImage source, int size) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        return resize(source.getSubimage(x, y, side, side), size, size);
    }

    private BufferedImage resizeToMaxWidth(BufferedImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) {
            return copyAsRgb(source);
        }
        int height = Math.max(1, (int) Math.round((double) source.getHeight() * maxWidth / source.getWidth()));
        return resize(source, maxWidth, height);
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, java.awt.Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private BufferedImage copyAsRgb(BufferedImage source) {
        return resize(source, source.getWidth(), source.getHeight());
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", output)) {
            throw new IOException("JPEG encoder를 찾을 수 없습니다.");
        }
        return output.toByteArray();
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private void deleteQuietly(String key) {
        try {
            storagePort.delete(key);
        } catch (RuntimeException e) {
            log.warn("이미지 variant 삭제 실패: {} ({})", key, e.getMessage());
        }
    }

    public record ChatImageVariants(
            String previewPath,
            long previewSize,
            String thumbnailPath,
            long thumbnailSize
    ) {
    }
}
