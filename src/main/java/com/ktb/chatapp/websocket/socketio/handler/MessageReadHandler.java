package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * [CHANGED] handler/MessageReadHandler.java — Last Read Watermark 방식으로 재작성.
 *
 * 메시지 읽음 상태 처리 핸들러. 메시지 읽음 상태 업데이트 및 브로드캐스트 담당.
 *
 * Before: 클라이언트가 messageIds 배열을 보내면, 그 중 첫 메시지를 조회해 roomId를
 *   알아내고, 서비스는 메시지 개수만큼 find+save를 반복했다.
 * After: 클라이언트가 roomId + lastReadMessageId(마지막으로 확인한 메시지 1건)만 보낸다.
 *   - roomId를 알아내려고 메시지를 조회할 필요가 없어졌다(클라이언트가 이미 그 방의
 *     소켓 룸에 들어와 있으므로 직접 보내는 게 자연스럽다).
 *   - 워터마크 비교 기준인 lastReadAt(메시지 timestamp)을 얻기 위해 메시지 1건만
 *     조회한다 — 메시지가 몇 개 읽혔든 이 조회는 항상 딱 1번이다.
 *   - 브로드캐스트도 "메시지 ID 목록"이 아니라 "워터마크 좌표 1개"로 가벼워졌다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {

    private final SocketIOServer socketIOServer;
    private final MessageReadStatusService messageReadStatusService;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (data == null || data.getRoomId() == null || data.getRoomId().isBlank()
                    || data.getLastReadMessageId() == null || data.getLastReadMessageId().isBlank()) {
                return;
            }

            String roomId = data.getRoomId();

            // lastReadAt(워터마크 비교 기준)을 얻기 위한 조회 1건.
            // 클라이언트가 보낸 roomId가 실제 메시지의 방과 일치하는지도 함께 검증한다.
            Message lastReadMessage = messageRepository.findById(data.getLastReadMessageId()).orElse(null);
            if (lastReadMessage == null || !roomId.equals(lastReadMessage.getRoomId())) {
                client.sendEvent(ERROR, Map.of("message", "Invalid message"));
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                client.sendEvent(ERROR, Map.of("message", "User not found"));
                return;
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null || !room.getParticipantIds().contains(userId)) {
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }

            messageReadStatusService.updateReadStatus(
                    roomId, userId, data.getLastReadMessageId(), lastReadMessage.getTimestamp());

            // [FIX] MessagesReadResponse의 lastReadAt은 epoch millis (long).
            MessagesReadResponse response = new MessagesReadResponse(
                    userId, roomId, data.getLastReadMessageId(), lastReadMessage.toTimestampMillis());

            // Broadcast to room
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGES_READ, response);

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
            ));
        }
    }

    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }
}
