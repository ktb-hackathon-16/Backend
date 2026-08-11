package com.ktb.chatapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PresignedUploadRequest(
        @NotBlank String originalFilename,
        @NotBlank String contentType,
        @Min(1) long size
) {
}
