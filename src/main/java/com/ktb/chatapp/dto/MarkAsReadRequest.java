package com.ktb.chatapp.dto;

import lombok.Data;

/**
 * [CHANGED] dto/MarkAsReadRequest.java
 *
 * Before: private List<String> messageIds;
 *   -> 클라이언트가 "읽은 메시지 ID 목록"을 통째로 보내고, 서버는 그 개수만큼
 *      find+save를 반복했다 (N+1 부하의 원인).
 *
 * After: roomId + lastReadMessageId 두 값만 받는다.
 *   -> "여기까지 읽었다"는 워터마크 한 지점만 알려주면 되므로, 서버는
 *      메시지 개수와 무관하게 항상 upsert 1번으로 처리한다 (Last Read Watermark 방식).
 *   -> roomId를 클라이언트가 직접 보내므로, 기존처럼 메시지 하나를 미리 조회해서
 *      roomId를 알아내는 추가 쿼리(MessageReadHandler의 findById)도 사라진다.
 */
@Data
public class MarkAsReadRequest {
    private String roomId;
    private String lastReadMessageId;
}
