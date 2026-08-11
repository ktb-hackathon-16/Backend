package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignedUploadCompleteRequest(
        @NotBlank String fileId
) {
}
