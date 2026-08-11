package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.ParticipantReadState;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.ReadReceiptRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 입장 처리 핸들러
 * 채팅방 입장, 참가자 관리, 초기 메시지 로드 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    private final MessageResponseMapper messageResponseMapper;
    private final RoomLeaveHandler roomLeaveHandler;
    // [ADDED] handler/RoomJoinHandler.java: 방 입장 시 참가자별 읽음 워터마크 스냅샷을
    // 내려주기 위해 추가. 참고: model/ReadReceipt.java, dto/ParticipantReadState.java
    private final ReadReceiptRepository readReceiptRepository;
    
    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
                return;
            }
            
            if (userRepository.findById(userId).isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                return;
            }
            
            if (roomRepository.findById(roomId).isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }
            
            // 이미 해당 방에 참여 중인지 확인
            if (userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} already in room {}", userId, roomId);
                client.joinRoom(roomId);
                client.sendEvent(JOIN_ROOM_SUCCESS, Map.of("roomId", roomId));
                return;
            }

            roomRepository.addParticipant(roomId, userId);

            // Join socket room and add to user's room set
            client.joinRoom(roomId);
            userRooms.add(userId, roomId);

            // [CHANGED] handler/RoomJoinHandler.java: .readers(new ArrayList<>()) 제거.
            // Message 모델에 readers 필드가 더는 없다 (Last Read Watermark 방식).
            Message joinMessage = Message.builder()
                .roomId(roomId)
                .content(userName + "님이 입장하였습니다.")
                .type(MessageType.system)
                .timestamp(LocalDateTime.now())
                .mentions(new ArrayList<>())
                .reactions(new HashMap<>())
                .metadata(new HashMap<>())
                .build();

            joinMessage = messageRepository.save(joinMessage);

            // 초기 메시지 로드
            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse messageLoadResult = messageLoader.loadMessages(req, userId);

            // 업데이트된 room 다시 조회하여 최신 participantIds 가져오기
            Optional<Room> roomOpt = roomRepository.findById(roomId);
            if (roomOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            // 참가자 정보 조회
            List<UserResponse> participants = loadParticipants(roomOpt.get());
            
            // [ADDED] handler/RoomJoinHandler.java: 참가자별 읽음 워터마크 스냅샷 조회.
            // 프론트는 이 값으로 room.readReceipts를 초기화하고, 이후로는
            // messagesRead 브로드캐스트(MessageReadHandler)만으로 갱신한다.
            // [FIX] lastReadAt은 epoch millis로 변환해서 내보낸다 (dto/ParticipantReadState 주석 참고).
            List<ParticipantReadState> participantReadStates = readReceiptRepository.findByRoomId(roomId)
                    .stream()
                    .map(receipt -> new ParticipantReadState(
                            receipt.getUserId(), receipt.getLastReadMessageId(), receipt.toLastReadAtMillis()))
                    .toList();

            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                .roomId(roomId)
                .participants(participants)
                .messages(messageLoadResult.getMessages())
                .hasMore(messageLoadResult.isHasMore())
                .nextCursor(messageLoadResult.getNextCursor())
                .activeStreams(Collections.emptyList())
                .participantReadStates(participantReadStates)
                .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            // 입장 메시지 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                .sendEvent(MESSAGE, messageResponseMapper.mapToMessageResponse(joinMessage, null));

            // 참가자 목록 업데이트 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                .sendEvent(PARTICIPANTS_UPDATE, participants);

            log.info("User {} joined room {} successfully. Message count: {}, hasMore: {}",
                userName, roomId, messageLoadResult.getMessages().size(), messageLoadResult.isHasMore());

        } catch (Exception e) {
            log.error("Error handling joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                "message", e.getMessage() != null ? e.getMessage() : "채팅방 입장에 실패했습니다."
            ));
        }
    }
    
    private SocketUser getUser(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.name() : null;
    }

    private List<UserResponse> loadParticipants(Room room) {
        Set<String> participantIds = room.getParticipantIds() == null
                ? Set.of()
                : room.getParticipantIds();
        if (participantIds.isEmpty()) {
            return List.of();
        }

        Map<String, User> usersById = userRepository.findSummariesByIdIn(participantIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, user -> user, (first, ignored) -> first));

        return participantIds.stream()
                .map(usersById::get)
                .filter(Objects::nonNull)
                .map(UserResponse::from)
                .toList();
    }
}
