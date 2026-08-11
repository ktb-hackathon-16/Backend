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
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessagesReadResponse {
    private String userId;
    private String roomId;
    private String lastReadMessageId;
    private java.time.LocalDateTime lastReadAt;
}
