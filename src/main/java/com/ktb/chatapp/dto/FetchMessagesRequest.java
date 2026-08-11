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

    /**
     * 기존 3개 인자 생성자와의 하위 호환을 유지한다.
     *
     * 기존 테스트와 RoomJoinHandler에서 사용하는
     * new FetchMessagesRequest(roomId, limit, before)가 계속 컴파일된다.
     */
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

    /**
     * 기존 timestamp 기반 pagination에서 사용한다.
     * 복합 cursor 전환이 끝날 때까지 유지한다.
     */
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