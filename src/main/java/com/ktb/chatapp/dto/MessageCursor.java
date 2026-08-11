package com.ktb.chatapp.dto;

/**
 * 메시지 keyset pagination 커서.
 * timestamp가 같은 메시지는 messageId를 이용해 순서를 결정한다.
 */
public record MessageCursor(
        Long timestamp,
        String messageId
) {
    public boolean isValid() {
        return timestamp != null
                && timestamp > 0
                && messageId != null
                && !messageId.isBlank();
    }
}
