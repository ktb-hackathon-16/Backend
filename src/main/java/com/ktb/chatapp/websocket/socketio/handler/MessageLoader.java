package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageResponseMapper messageResponseMapper;
    private final MessageReadStatusService messageReadStatusService;

    private static final int BATCH_SIZE = 30;

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(data.roomId(), data.limit(BATCH_SIZE), data.before(LocalDateTime.now()), userId);
        } catch (Exception e) {
            log.error("Error loading initial messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> messagePage = messageRepository
                .findByRoomIdAndTimestampBefore(roomId, before, pageable);

        List<Message> messages = messagePage.getContent();

        // DESC로 조회했으므로 ASC로 재정렬 (채팅 UI 표시 순서)
        List<Message> sortedMessages = messages.reversed();

        // [CHANGED] handler/MessageLoader.java: 예전엔 조회된 메시지 ID를 전부 모아
        // updateReadStatus(messageIds, userId)로 넘겨 메시지 개수만큼 DB에 쓰기를 반복했다.
        // Last Read Watermark 방식에서는 "이 배치에서 가장 최신 메시지"까지만 워터마크를
        // 전진시키면 그 이전 메시지는 전부 읽은 것으로 간주되므로, 마지막 메시지 1건의
        // id/timestamp만 넘긴다.
        if (!sortedMessages.isEmpty()) {
            Message latestInBatch = sortedMessages.getLast();
            messageReadStatusService.updateReadStatus(
                    roomId, userId, latestInBatch.getId(), latestInBatch.getTimestamp());
        }

        // 메시지 응답 생성
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> {
                    var user = findUserById(message.getSenderId());
                    return messageResponseMapper.mapToMessageResponse(message, user);
                })
                .collect(Collectors.toList());

        boolean hasMore = messagePage.hasNext();

        log.debug("Messages loaded - roomId: {}, limit: {}, count: {}, hasMore: {}",
                roomId, limit, messageResponses.size(), hasMore);

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }

    /**
     * AI 경우 null 반환 가능
     */
    @Nullable
    private User findUserById(String id) {
        if (id == null) {
            return null;
        }
        return userRepository.findById(id)
                .orElse(null);
    }
}
