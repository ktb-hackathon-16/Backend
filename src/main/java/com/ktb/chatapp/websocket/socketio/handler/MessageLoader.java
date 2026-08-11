package com.ktb.chatapp.websocket.socketio.handler;

import static java.util.Collections.emptyList;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageCursor;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(
            FetchMessagesRequest data,
            String userId
    ) {
        try {
            int limit = Math.min(
                    data.limit(BATCH_SIZE),
                    MAX_BATCH_SIZE
            );

            if (data.cursor() != null && !data.cursor().isValid()) {
                throw new IllegalArgumentException(
                        "timestamp와 messageId가 모두 포함된 cursor가 필요합니다."
                );
            }

            /*
             * 새로운 요청:
             * - 최초 조회(cursor와 before가 모두 없음)
             * - 복합 cursor 조회
             */
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

            /*
             * 기존 클라이언트 하위 호환:
             * before만 전달된 경우 기존 Page 조회를 임시 유지한다.
             */
            return loadLegacyMessages(
                    data.roomId(),
                    limit,
                    data.before(LocalDateTime.now()),
                    userId
            );
        } catch (Exception e) {
            log.error(
                    "Error loading messages for room {}",
                    data.roomId(),
                    e
            );

            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .nextCursor(null)
                    .build();
        }
    }

    /**
     * 복합 keyset cursor + 수동 limit+1 조회
     */
    private FetchMessagesResponse loadKeysetMessages(
            String roomId,
            int limit,
            @Nullable MessageCursor cursor,
            String userId
    ) {
        int fetchSize = limit + 1;

        List<Message> fetchedMessages =
                messageRepository.findOlderMessages(
                        roomId,
                        cursor,
                        fetchSize
                );

        boolean hasMore = fetchedMessages.size() > limit;

        int responseSize = Math.min(
                fetchedMessages.size(),
                limit
        );

        List<Message> messages = new ArrayList<>(
                fetchedMessages.subList(0, responseSize)
        );

        MessageCursor nextCursor =
                createNextCursor(messages, hasMore);

        return createResponse(
                roomId,
                limit,
                messages,
                hasMore,
                nextCursor,
                userId
        );
    }

    /**
     * 기존 timestamp 기반 조회.
     * 프론트엔드 cursor 전환 완료 후 제거한다.
     */
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

        List<Message> messages =
                new ArrayList<>(messagePage.getContent());

        boolean hasMore = messagePage.hasNext();

        /*
         * 기존 before 요청에도 nextCursor를 내려준다.
         * 클라이언트가 다음 요청부터 cursor 방식으로 전환할 수 있다.
         */
        MessageCursor nextCursor =
                createNextCursor(messages, hasMore);

        return createResponse(
                roomId,
                limit,
                messages,
                hasMore,
                nextCursor,
                userId
        );
    }

    /**
     * DB에서 DESC로 조회한 메시지를 채팅 UI용 ASC 순서로 변환한다.
     */
    private FetchMessagesResponse createResponse(
            String roomId,
            int limit,
            List<Message> messagesDescending,
            boolean hasMore,
            @Nullable MessageCursor nextCursor,
            String userId
    ) {
        List<Message> sortedMessages =
                new ArrayList<>(messagesDescending);

        Collections.reverse(sortedMessages);

        var messageIds = sortedMessages.stream()
                .map(Message::getId)
                .toList();

        messageReadStatusService.updateReadStatus(
                messageIds,
                userId
        );

        /*
         * 사용자 조회 로직은 기존 코드 그대로 유지한다.
         * N+1 개선 범위는 이번 pagination 작업에 포함하지 않는다.
         */
        List<MessageResponse> messageResponses =
                sortedMessages.stream()
                        .map(message -> {
                            var user = findUserById(
                                    message.getSenderId()
                            );

                            return messageResponseMapper
                                    .mapToMessageResponse(
                                            message,
                                            user
                                    );
                        })
                        .collect(Collectors.toList());

        log.debug(
                "Messages loaded - roomId: {}, limit: {}, "
                        + "count: {}, hasMore: {}, nextCursor: {}",
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

    /**
     * 현재 페이지에서 가장 오래된 메시지를 다음 cursor로 사용한다.
     *
     * messagesDescending:
     * 최신 메시지 -> 오래된 메시지 순서
     */
    @Nullable
    private MessageCursor createNextCursor(
            List<Message> messagesDescending,
            boolean hasMore
    ) {
        if (!hasMore || messagesDescending.isEmpty()) {
            return null;
        }

        Message oldestMessage =
                messagesDescending.getLast();

        return new MessageCursor(
                oldestMessage.toTimestampMillis(),
                oldestMessage.getId()
        );
    }

    /**
     * AI 메시지인 경우 null 반환 가능
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