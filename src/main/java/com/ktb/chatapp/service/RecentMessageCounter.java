package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    public Map<String, Integer> countRecentMessagesByRoomIds(List<String> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }

        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return messageRepository.countRecentMessagesByRoomIds(roomIds, since).stream()
                .collect(Collectors.toMap(
                        MessageRepository.RecentMessageCount::getRoom,
                        count -> (int) count.getCount()));
    }
}
