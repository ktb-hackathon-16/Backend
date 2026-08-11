package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomActivityNotifierTest {

    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private ApplicationEventPublisher eventPublisher;
    private RoomActivityNotifier currentNotifier;

    private RoomActivityNotifier notifier() {
        currentNotifier = new RoomActivityNotifier(
                recentMessageCounter,
                eventPublisher,
                Executors.newSingleThreadScheduledExecutor());
        return currentNotifier;
    }

    @AfterEach
    void tearDown() {
        if (currentNotifier != null) {
            currentNotifier.shutdown();
        }
    }

    @Test
    void notifyMessageStored_firstMessageOfRoom_publishesRecentMessageCount() {
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(7);

        notifier().notifyMessageStored("room-1");

        ArgumentCaptor<RoomActivityEvent> eventCaptor =
                ArgumentCaptor.forClass(RoomActivityEvent.class);
        verify(eventPublisher, timeout(4000)).publishEvent(eventCaptor.capture());
        assertEquals("room-1", eventCaptor.getValue().getRoomId());
        assertEquals(7, eventCaptor.getValue().getRecentMessageCount());
    }

    @Test
    void notifyMessageStored_messagesInWindow_publishOnce() {
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(1);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored("room-1");
        notifier.notifyMessageStored("room-1");
        notifier.notifyMessageStored("room-1");

        verify(eventPublisher, timeout(4000).times(1)).publishEvent(any(RoomActivityEvent.class));
        verify(recentMessageCounter, timeout(4000).times(1)).countRecentMessages("room-1");
    }

    @Test
    void notifyMessageStored_nullRoomId_doesNothing() {
        notifier().notifyMessageStored(null);

        verifyNoInteractions(recentMessageCounter);
        verify(eventPublisher, never()).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_counterFails_swallowsException() {
        when(recentMessageCounter.countRecentMessages("room-1"))
                .thenThrow(new RuntimeException("mongo down"));

        notifier().notifyMessageStored("room-1");

        verify(recentMessageCounter, timeout(4000)).countRecentMessages("room-1");
        verify(eventPublisher, timeout(4000).times(0)).publishEvent(any(RoomActivityEvent.class));
    }
}
