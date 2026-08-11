package com.ktb.chatapp.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 이전 메시지 조회 요청.
 *
 * cursor가 있으면 복합 keyset pagination을 사용하고,
 * cursor가 없고 before가 있으면 기존 timestamp pagination을 사용한다.
 * 둘 다 없으면 최초 메시지 페이지를 조회한다.
 */
public record FetchMessagesRequest(
        String roomId,
        Integer limit,
        Long before,
        MessageCursor cursor
) {

    public FetchMessagesRequest(
            String roomId,
            Integer limit,
            Long before
    ) {
        this(roomId, limit, before, null);
    }

    public int limit(int defaultLimit) {
        return limit != null && limit > 0
                ? limit
                : defaultLimit;
    }
    
    public LocalDateTime before(LocalDateTime defaultBeforeTime) {
        if (before != null && before > 0) {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(before),
                    ZoneId.systemDefault()
            );
        }

        return defaultBeforeTime;
    }

    public boolean hasCursor() {
        return cursor != null;
    }
}
