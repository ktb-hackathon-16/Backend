package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 새 메시지가 저장되면 채팅방 목록의 활성도 지표를 갱신하도록 알린다.
 */
@Slf4j
@Component
public class RoomActivityNotifier {

    static final Duration FIXED_WINDOW = Duration.ofSeconds(3);

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService scheduler;
    private final Set<String> pendingRooms = ConcurrentHashMap.newKeySet();
    private final Set<String> scheduledRooms = ConcurrentHashMap.newKeySet();

    @Autowired
    public RoomActivityNotifier(
            RecentMessageCounter recentMessageCounter,
            ApplicationEventPublisher eventPublisher) {
        this(
                recentMessageCounter,
                eventPublisher,
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            Thread thread = new Thread(task, "room-activity-coalescer");
                            thread.setDaemon(true);
                            return thread;
                        }));
    }

    RoomActivityNotifier(
            RecentMessageCounter recentMessageCounter,
            ApplicationEventPublisher eventPublisher,
            ScheduledExecutorService scheduler) {
        this.recentMessageCounter = recentMessageCounter;
        this.eventPublisher = eventPublisher;
        this.scheduler = scheduler;
    }

    public void notifyMessageStored(String roomId) {
        if (roomId == null) {
            return;
        }

        pendingRooms.add(roomId);
        if (scheduledRooms.add(roomId)) {
            schedule(roomId);
        }
    }

    private void schedule(String roomId) {
        try {
            scheduler.schedule(
                    () -> flush(roomId),
                    FIXED_WINDOW.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            scheduledRooms.remove(roomId);
            pendingRooms.remove(roomId);
            log.error("roomActivity 예약 실패: roomId={}", roomId, e);
        }
    }

    private void flush(String roomId) {
        pendingRooms.remove(roomId);
        try {
            int recentMessageCount = recentMessageCounter.countRecentMessages(roomId);
            eventPublisher.publishEvent(new RoomActivityEvent(this, roomId, recentMessageCount));
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발행 실패: roomId={}", roomId, e);
        } finally {
            scheduledRooms.remove(roomId);
            if (pendingRooms.contains(roomId) && scheduledRooms.add(roomId)) {
                schedule(roomId);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
