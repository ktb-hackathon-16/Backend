package com.ktb.chatapp.dto;

/**
 * [NEW FILE] dto/ParticipantReadState.java
 *
 * 방 입장(joinRoomSuccess) 시 참가자별 현재 읽음 워터마크 스냅샷 1건.
 * 프론트는 방에 들어오자마자 이 목록을 받아 room.readReceipts 초기값을 채우고,
 * 이후로는 messagesRead 브로드캐스트(MessagesReadResponse)로만 갱신한다.
 * 새 소켓 이벤트를 추가하지 않고 기존 joinRoomSuccess 응답에 얹는 방식이라
 * socket-contract의 이벤트 개수(SERVER_EMIT 9개)는 그대로 유지된다.
 *
 * [FIX] lastReadAt은 epoch millis(long)로 내보낸다.
 * MessagesReadResponse와 같은 이유 — LocalDateTime을 그대로 직렬화하면 배열이 되어
 * 프론트의 new Date(...)가 Invalid Date를 만든다. MessageResponse.timestamp와
 * 동일한 단위(epoch millis)로 맞춰 숫자끼리 비교되게 한다.
 */
public record ParticipantReadState(String userId, String lastReadMessageId, long lastReadAt) {
}
