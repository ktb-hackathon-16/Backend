package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.ChatMessageRequest;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.RateLimitCheckResult;
import com.ktb.chatapp.service.RateLimitService;
import com.ktb.chatapp.service.RoomActivityNotifier;
import com.ktb.chatapp.service.RecentMessageCounter;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.util.BannedWordChecker;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.ai.AiService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMessageHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageRepository messageRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileRepository fileRepository;
    @Mock private AiService aiService;
    @Mock private SessionService sessionService;
    @Mock private RoomActivityNotifier roomActivityNotifier;
    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private BannedWordChecker bannedWordChecker;
    @Mock private RateLimitService rateLimitService;
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ChatMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler =
                new ChatMessageHandler(
                        socketIOServer,
                        messageRepository,
                        roomRepository,
                        userRepository,
                        fileRepository,
                        aiService,
                        sessionService,
                        roomActivityNotifier,
                        recentMessageCounter,
                        bannedWordChecker,
                        rateLimitService,
                        meterRegistry);
    }

    @Test
    void handleChatMessage_blocksMessagesContainingBannedWords() {
        SocketIOClient client = mock(SocketIOClient.class);
        SocketUser socketUser = new SocketUser(
                "user-1",
                "tester",
                "session-1",
                "socket-1");

        when(client.get("user")).thenReturn(socketUser);
        when(sessionService.validateSession(
                socketUser.id(),
                socketUser.authSessionId()))
                .thenReturn(SessionValidationResult.valid(null));
        when(rateLimitService.checkRateLimit(
                eq(socketUser.id()),
                anyInt(),
                any()))
                .thenReturn(RateLimitCheckResult.allowed(
                        10000,
                        9999,
                        60,
                        System.currentTimeMillis() / 1000 + 60,
                        60));

        User user = new User();
        user.setId("user-1");
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(user));

        Room room = new Room();
        room.setId("room-1");
        room.setParticipantIds(new HashSet<>(java.util.List.of("user-1")));
        when(roomRepository.findById("room-1"))
                .thenReturn(Optional.of(room));

        ChatMessageRequest request =
                ChatMessageRequest.builder()
                        .room("room-1")
                        .type("text")
                        .content("bad word")
                        .build();

        when(bannedWordChecker.containsBannedWord("bad word"))
                .thenReturn(true);

        handler.handleChatMessage(client, request);

        ArgumentCaptor<Map<String, String>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(client).sendEvent(eq(ERROR), payloadCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "MESSAGE_REJECTED",
                payloadCaptor.getValue().get("code"));

        verifyNoInteractions(messageRepository);
        verifyNoInteractions(recentMessageCounter);
        verifyNoInteractions(roomActivityNotifier);
        verifyNoInteractions(aiService);
        verify(socketIOServer, never()).getRoomOperations(any());
        verify(sessionService, never()).updateLastActivity(anyString());
    }

//    @Test
//    void handleChatMessage_broadcastsMessageWithoutDirectSenderEcho() {
//        SocketIOClient client = mock(SocketIOClient.class);
//        BroadcastOperations roomOperations =
//                mock(BroadcastOperations.class);
//
//        SocketUser socketUser = new SocketUser(
//                "user-1",
//                "tester",
//                "session-1",
//                "socket-1");
//
//        when(client.get("user"))
//                .thenReturn(socketUser);
//
//        // validateSession()이 세션 검증과 활동 시간 갱신을 담당한다.
//        when(sessionService.validateSession(
//                socketUser.id(),
//                socketUser.authSessionId()))
//                .thenReturn(SessionValidationResult.valid(null));
//
//        when(rateLimitService.checkRateLimit(
//                eq(socketUser.id()),
//                anyInt(),
//                any()))
//                .thenReturn(
//                        RateLimitCheckResult.allowed(
//                                10000,
//                                9999,
//                                60,
//                                System.currentTimeMillis() / 1000 + 60,
//                                60));
//
//        User user = new User();
//        user.setId("user-1");
//        user.setName("Tester");
//
//        when(userRepository.findById("user-1"))
//                .thenReturn(Optional.of(user));
//
//        Room room = new Room();
//        room.setId("room-1");
//        room.setParticipantIds(
//                new HashSet<>(
//                        java.util.List.of("user-1")));
//
//        when(roomRepository.findById("room-1"))
//                .thenReturn(Optional.of(room));
//
//        when(bannedWordChecker.containsBannedWord("hello"))
//                .thenReturn(false);
//
//        when(socketIOServer.getRoomOperations("room-1"))
//                .thenReturn(roomOperations);
//
//        when(messageRepository.save(any(Message.class)))
//                .thenAnswer(invocation -> {
//                    Message message = invocation.getArgument(0);
//                    message.setId("message-1");
//                    message.setTimestamp(
//                            LocalDateTime.of(
//                                    2026,
//                                    7,
//                                    7,
//                                    9,
//                                    0));
//                    message.setType(MessageType.text);
//                    return message;
//                });
//
//        ChatMessageRequest request =
//                ChatMessageRequest.builder()
//                        .room("room-1")
//                        .type("text")
//                        .content("hello")
//                        .build();
//
//        handler.handleChatMessage(client, request);
//
//        ArgumentCaptor<MessageResponse> payloadCaptor =
//                ArgumentCaptor.forClass(
//                        MessageResponse.class);
//
//        // 저장된 메시지는 Socket.IO room 전체에 정확히 한 번 전송된다.
//        verify(roomOperations, times(1))
//                .sendEvent(
//                        eq(MESSAGE),
//                        payloadCaptor.capture());
//
//        // 발신자에게 동일 메시지를 별도로 전송하면 안 된다.
//        verify(client, never())
//                .sendEvent(
//                        eq(MESSAGE),
//                        any(MessageResponse.class));
//
//        // validateSession()은 메시지 처리 중 한 번만 호출된다.
//        verify(sessionService, times(1))
//                .validateSession(
//                        socketUser.id(),
//                        socketUser.authSessionId());
//
//        // validateSession()이 이미 활동 시간을 갱신하므로
//        // 별도의 updateLastActivity() 호출이 없어야 한다.
//        verify(sessionService, never())
//                .updateLastActivity(anyString());
//
//        // 저장된 메시지는 최근 메시지 카운터에 기록된다.
//        verify(recentMessageCounter, times(1))
//                .recordMessage(any(Message.class));
//
//        verify(roomActivityNotifier, times(1))
//                .notifyMessageStored("room-1");
//
//        MessageResponse response =
//                payloadCaptor.getValue();
//
//        org.junit.jupiter.api.Assertions.assertEquals(
//                "message-1",
//                response.getId());
//
//        org.junit.jupiter.api.Assertions.assertEquals(
//                "hello",
//                response.getContent());
//    }
}
