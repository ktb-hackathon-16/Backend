package com.ktb.chatapp.dto;

/**
 * [NEW FILE] dto/ParticipantReadState.java
 *
 * 방 입장(joinRoomSuccess) 시 참가자별 현재 읽음 워터마크 스냅샷 1건.
 * 프론트는 방에 들어오자마자 이 목록을 받아 room.readReceipts 초기값을 채우고,
 * 이후로는 messagesRead 브로드캐스트(MessagesReadResponse)로만 갱신한다.
 * 새 소켓 이벤트를 추가하지 않고 기존 joinRoomSuccess 응답에 얹는 방식이라
 * socket-contract의 이벤트 개수(SERVER_EMIT 9개)는 그대로 유지된다.
 */
// [FIX] lastReadAt을 epoch millis (long)로 변경. MessagesReadResponse와 일관성 유지.
public record ParticipantReadState(String userId, String lastReadMessageId, long lastReadAt) {
}
