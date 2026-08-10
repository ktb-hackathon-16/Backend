package com.ktb.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * joinRoomSuccess 이벤트 응답 DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoinRoomSuccessResponse {
    private String roomId;
    private List<UserResponse> participants;
    private List<MessageResponse> messages;
    private boolean hasMore;
    private List<ActiveStreamResponse> activeStreams;

    // [ADDED] dto/JoinRoomSuccessResponse.java: 참가자별 읽음 워터마크 스냅샷.
    // 프론트가 방 입장 시 이 값으로 room.readReceipts를 초기화해 안읽음 배지를 계산한다.
    private List<ParticipantReadState> participantReadStates;
}
