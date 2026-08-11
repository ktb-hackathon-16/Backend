package com.ktb.chatapp.storage;

/**
 * 스토리지 key 규약: {@code profiles/<name>}(구 프로필), {@code media/profiles/...}(CDN 공개),
 * {@code media/chat/...}(CDN 공개 미리보기), {@code chat/<name>}(인가 필요 원본).
 */
public final class StorageKey {

    private static final String PROFILE_PREFIX = "profiles/";
    private static final String MEDIA_PROFILE_PREFIX = "media/profiles/";
    private static final String MEDIA_CHAT_PREVIEW_PREFIX = "media/chat/previews/";
    private static final String MEDIA_CHAT_THUMBNAIL_PREFIX = "media/chat/thumbnails/";
    private static final String CHAT_PREFIX = "chat/";

    private StorageKey() {
    }

    public static String profile(String fileName) {
        return PROFILE_PREFIX + fileName;
    }

    public static String mediaProfile(String userId, String fileName) {
        return MEDIA_PROFILE_PREFIX + userId + "/" + fileName;
    }

    public static String mediaChatPreview(String fileName) {
        return MEDIA_CHAT_PREVIEW_PREFIX + fileName;
    }

    public static String mediaChatThumbnail(String fileName) {
        return MEDIA_CHAT_THUMBNAIL_PREFIX + fileName;
    }

    public static String chat(String fileName) {
        return CHAT_PREFIX + fileName;
    }

    public static boolean isProfile(String key) {
        return key != null && key.startsWith(PROFILE_PREFIX);
    }

    public static boolean isMedia(String key) {
        return key != null && key.startsWith("media/");
    }

    public static boolean isChat(String key) {
        return key != null && key.startsWith(CHAT_PREFIX);
    }

    public static String nameOf(String key) {
        if (isProfile(key)) {
            return key.substring(PROFILE_PREFIX.length());
        }
        if (isChat(key)) {
            return key.substring(CHAT_PREFIX.length());
        }
        return key;
    }
}
