package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private RoomService roomService;

    @Test
    void getAllRooms_usesPagedQueryAndReturnsMetadata() {
        PageRequest pageRequest = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Room room = Room.builder()
                .id("room-1")
                .name("부하테스트 방")
                .creator("user-1")
                .participantIds(Set.of("user-1", "user-2"))
                .createdAt(LocalDateTime.now())
                .build();
        User creator = User.builder()
                .id("user-1")
                .name("creator")
                .email("creator@example.com")
                .build();
        when(roomRepository.findRooms(pageRequest))
                .thenReturn(new PageImpl<>(List.of(room), pageRequest, 5));
        when(userRepository.findSummariesByIdIn(Set.of("user-1")))
                .thenReturn(List.of(creator));
        when(recentMessageCounter.countRecentMessagesByRoomIds(List.of("room-1")))
                .thenReturn(Map.of("room-1", 3));

        RoomsResponse response = roomService.getAllRooms("creator@example.com", 1, 2);

        assertTrue(response.isSuccess());
        assertEquals(1, response.getData().size());
        assertEquals("room-1", response.getData().getFirst().getId());
        assertEquals(3, response.getData().getFirst().getRecentMessageCount());
        assertEquals(2, response.getData().getFirst().getParticipants().size());
        assertTrue(response.getData().getFirst().getParticipants().stream()
                .allMatch(participant -> participant.getName() == null && participant.getEmail() == null));
        assertTrue(response.getData().getFirst().getParticipants().stream()
                .anyMatch(participant -> "user-1".equals(participant.getId())));
        assertTrue(response.getData().getFirst().getParticipants().stream()
                .anyMatch(participant -> "user-2".equals(participant.getId())));
        assertEquals("creator", response.getData().getFirst().getCreator().getName());
        assertEquals(5, response.getMetadata().getTotal());
        assertEquals(1, response.getMetadata().getPage());
        assertEquals(2, response.getMetadata().getPageSize());
        assertEquals(3, response.getMetadata().getTotalPages());
        assertTrue(response.getMetadata().isHasMore());
        assertEquals(1, response.getMetadata().getCurrentCount());
        assertEquals("createdAt", response.getMetadata().getSort().getField());
        assertEquals("desc", response.getMetadata().getSort().getOrder());
        verify(roomRepository).findRooms(pageRequest);
        verify(userRepository).findSummariesByIdIn(Set.of("user-1"));
        verifyNoMoreInteractions(userRepository);
        verify(recentMessageCounter).warmupRecentMessagesByRoomIds(List.of("room-1"));
    }
}
