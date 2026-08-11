package com.ktb.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [CHANGED] dto/MessagesReadResponse.java
 *
 * Before: { userId, List<String> messageIds } - 읽은 메시지 ID를 전부 브로드캐스트.
 * After: { userId, roomId, lastReadMessageId, lastReadAt } - 워터마크 1개만 브로드캐스트.
 *
 * 프론트는 이 워터마크를 받아서 "이 유저가 lastReadAt 이전 메시지는 다 읽었다"고
 * 로컬에서 계산한다 (메시지마다 readers 배열을 서버가 유지/전송할 필요가 없어짐).
 *
 * [FIX] lastReadAt을 LocalDateTime -> long(epoch millis)으로 변경.
 * LocalDateTime을 그대로 내보내면 socket.io의 JacksonJsonSupport(JavaTimeModule)가
 * 배열 형태([2026,8,11,9,48,37,...])로 직렬화한다. 프론트의 new Date(...)는 이걸
 * 파싱하지 못해 Invalid Date(NaN)가 되고, 워터마크 비교가 항상 false가 되어
 * "N명 안 읽음"이 영원히 줄지 않았다.
 * MessageResponse.timestamp가 이미 epoch millis(long)이므로 같은 단위로 맞춘다.
 * 프론트는 두 숫자를 그대로 비교하면 되고 타임존 해석 여지가 사라진다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessagesReadResponse {
    private String userId;
    private String roomId;
    private String lastReadMessageId;
    private long lastReadAt;
}
