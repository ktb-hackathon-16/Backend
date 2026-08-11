package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
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
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// [CHANGED] test/.../MessageReadHandlerTest.java
// Last Read Watermark 방식 전환에 따라 MarkAsReadRequest가 messageIds 배열 대신
// roomId + lastReadMessageId를 받도록 바뀐 것에 맞춰 테스트를 다시 작성했다.
@ExtendWith(MockitoExtension.class)
class MessageReadHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageReadStatusService messageReadStatusService;
    @Mock private MessageRepository messageRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private MessageReadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageReadHandler(
                socketIOServer,
                messageReadStatusService,
                messageRepository,
                roomRepository,
                userRepository);
    }

    @Test
    void handleMarkAsRead_rejectsUnauthorizedClient() {
        MarkAsReadRequest request = request("room-1", "message-1");
        when(client.get("user")).thenReturn(null);

        handler.handleMarkAsRead(client, request);

        verify(client).sendEvent(eq(ERROR), any());
        verify(messageReadStatusService, never()).updateReadStatus(any(), any(), any(), any());
    }

    @Test
    void handleMarkAsRead_updatesStatusAndBroadcasts() {
        MarkAsReadRequest request = request("room-1", "message-1");
        LocalDateTime lastReadAt = LocalDateTime.of(2026, 8, 10, 12, 0, 0);
        Message message = Message.builder()
                .id("message-1")
                .roomId("room-1")
                .timestamp(lastReadAt)
                .build();
        Room room = Room.builder().id("room-1").participantIds(Set.of("user-1")).build();
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMarkAsRead(client, request);

        verify(messageReadStatusService).updateReadStatus("room-1", "user-1", "message-1", lastReadAt);
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(MESSAGES_READ), responseCaptor.capture());
        MessagesReadResponse response = (MessagesReadResponse) responseCaptor.getValue();
        assertEquals("user-1", response.getUserId());
        assertEquals("room-1", response.getRoomId());
        assertEquals("message-1", response.getLastReadMessageId());
        assertEquals(lastReadAt, response.getLastReadAt());
    }

    private MarkAsReadRequest request(String roomId, String lastReadMessageId) {
        MarkAsReadRequest request = new MarkAsReadRequest();
        request.setRoomId(roomId);
        request.setLastReadMessageId(lastReadMessageId);
        return request;
    }
}
