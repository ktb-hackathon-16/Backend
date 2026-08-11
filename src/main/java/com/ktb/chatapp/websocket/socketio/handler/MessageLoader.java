package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageCursor;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.service.RecentMessageCache;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private static final int BATCH_SIZE = 30;
    private static final int MAX_BATCH_SIZE = 100;

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageResponseMapper messageResponseMapper;
    private final MessageReadStatusService messageReadStatusService;
    private final RecentMessageCache recentMessageCache;

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(
            FetchMessagesRequest data,
            String userId
    ) {
        if (data == null) {
            throw new IllegalArgumentException("메시지 조회 요청은 필수입니다.");
        }

        int limit = Math.min(data.limit(BATCH_SIZE), MAX_BATCH_SIZE);

        if (data.cursor() != null && !data.cursor().isValid()) {
            throw new IllegalArgumentException(
                    "timestamp와 messageId가 모두 포함된 cursor가 필요합니다."
            );
        }

        if (data.hasCursor()
                || data.before() == null
                || data.before() <= 0) {
            return loadKeysetMessages(
                    data.roomId(),
                    limit,
                    data.cursor(),
                    userId
            );
        }

        return loadLegacyMessages(
                data.roomId(),
                limit,
                data.before(LocalDateTime.now()),
                userId
        );
    }

    private FetchMessagesResponse loadKeysetMessages(
            String roomId,
            int limit,
            @Nullable MessageCursor cursor,
            String userId
    ) {
        int fetchSize = limit + 1;

        List<Message> fetchedMessages = recentMessageCache
                .findOlderMessages(roomId, cursor, fetchSize)
                .orElseGet(() -> {
                    List<Message> messages = messageRepository.findOlderMessages(
                            roomId,
                            cursor,
                            fetchSize
                    );
                    recentMessageCache.put(roomId, messages);
                    return messages;
                });

        boolean hasMore = fetchedMessages.size() > limit;
        int responseSize = Math.min(fetchedMessages.size(), limit);
        List<Message> messages = new ArrayList<>(
                fetchedMessages.subList(0, responseSize)
        );
        MessageCursor nextCursor = createNextCursor(messages, hasMore);

        return createResponse(
                roomId,
                limit,
                messages,
                hasMore,
                nextCursor,
                cursor == null,
                userId
        );
    }

    private FetchMessagesResponse loadLegacyMessages(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId
    ) {
        Pageable pageable = PageRequest.of(
                0,
                limit,
                Sort.by("timestamp").descending()
        );

        Page<Message> messagePage =
                messageRepository.findByRoomIdAndTimestampBefore(
                        roomId,
                        before,
                        pageable
                );

        boolean hasMore = messagePage.hasNext();
        List<Message> messages = new ArrayList<>(messagePage.getContent());
        MessageCursor nextCursor = createNextCursor(messages, hasMore);

        return createResponse(
                roomId,
                limit,
                messages,
                hasMore,
                nextCursor,
                false,
                userId
        );
    }

    private FetchMessagesResponse createResponse(
            String roomId,
            int limit,
            List<Message> messagesDescending,
            boolean hasMore,
            @Nullable MessageCursor nextCursor,
            boolean updateReadWatermark,
            String userId
    ) {
        List<Message> sortedMessages = new ArrayList<>(messagesDescending);
        Collections.reverse(sortedMessages);

        if (updateReadWatermark && !sortedMessages.isEmpty()) {
            Message latestInBatch = sortedMessages.getLast();
            messageReadStatusService.updateReadStatus(
                    roomId,
                    userId,
                    latestInBatch.getId(),
                    latestInBatch.getTimestamp()
            );
        }

        Map<String, User> usersById = loadUsers(sortedMessages);
        List<MessageResponse> messageResponses = messageResponseMapper
                .mapToMessageResponses(sortedMessages, usersById);

        log.debug(
                "Messages loaded - roomId: {}, limit: {}, count: {}, "
                        + "hasMore: {}, nextCursor: {}",
                roomId,
                limit,
                messageResponses.size(),
                hasMore,
                nextCursor
        );

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .build();
    }

    @Nullable
    private MessageCursor createNextCursor(
            List<Message> messagesDescending,
            boolean hasMore
    ) {
        if (!hasMore || messagesDescending.isEmpty()) {
            return null;
        }

        Message oldestMessage = messagesDescending.getLast();
        return new MessageCursor(
                oldestMessage.toTimestampMillis(),
                oldestMessage.getId()
        );
    }

    private Map<String, User> loadUsers(List<Message> messages) {
        Set<String> senderIds = messages.stream()
                .map(Message::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (senderIds.isEmpty()) {
            return Map.of();
        }

        return userRepository.findSummariesByIdIn(senderIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, user -> user, (first, ignored) -> first));
    }
}
