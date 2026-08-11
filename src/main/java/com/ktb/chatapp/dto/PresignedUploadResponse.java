package com.ktb.chatapp.dto;

import java.util.Map;

public record PresignedUploadResponse(
        String fileId,
        String filename,
        String key,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        long expiresInSeconds,
        FileResponse file
) {
}
