package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private static final int DEFAULT_ROOM_PAGE_SIZE = 50;
    private static final int MAX_ROOM_PAGE_SIZE = 100;

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RecentMessageCounter recentMessageCounter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public RoomsResponse getAllRooms(String name) {
        return getAllRooms(name, 0, DEFAULT_ROOM_PAGE_SIZE);
    }

    public RoomsResponse getAllRooms(String name, int page, int limit) {

        try {
            int pageNumber = Math.max(page, 0);
            int pageSize = normalizeRoomPageSize(limit);
            PageRequest pageRequest = PageRequest.of(
                    pageNumber,
                    pageSize,
                    Sort.by(Sort.Direction.DESC, "createdAt"));

            Page<Room> roomPage = roomRepository.findRooms(pageRequest);
            List<Room> rooms = roomPage.getContent();
            Map<String, User> creatorsById = loadCreators(rooms);
            List<String> roomIds = rooms.stream()
                    .map(Room::getId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            Map<String, Integer> recentMessageCounts = recentMessageCounter.countRecentMessagesByRoomIds(roomIds);
            recentMessageCounter.warmupRecentMessagesByRoomIds(roomIds);

            List<RoomResponse> roomResponses = rooms.stream()
                .map(room -> mapToRoomListResponse(room, name, creatorsById, recentMessageCounts))
                .collect(Collectors.toList());

            PageMetadata metadata = PageMetadata.builder()
                .total(roomPage.getTotalElements())
                .page(pageNumber)
                .pageSize(pageSize)
                .totalPages(roomPage.getTotalPages())
                .hasMore(roomPage.hasNext())
                .currentCount(roomResponses.size())
                .sort(PageMetadata.SortInfo.builder()
                        .field("createdAt")
                        .order("desc")
                        .build())
                .build();

            return RoomsResponse.builder()
                .success(true)
                .data(roomResponses)
                .metadata(metadata)
                .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                .success(false)
                .data(List.of())
                .build();
        }
    }

    private int normalizeRoomPageSize(int limit) {
        if (limit <= 0) {
            return DEFAULT_ROOM_PAGE_SIZE;
        }
        return Math.min(limit, MAX_ROOM_PAGE_SIZE);
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                .connected(isMongoConnected)
                .latency(latency)
                .build());

            return HealthResponse.builder()
                .success(true)
                .services(services)
                .lastActivity(lastActivity)
                .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                .success(false)
                .services(new HashMap<>())
                .build();
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        return createRoomWithResponse(createRoomRequest, name).room();
    }

    public RoomOperationResult createRoomWithResponse(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);
        
        // Publish event for room created
        try {
            RoomResponse roomResponse = mapToRoomResponse(savedRoom, name);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
            return new RoomOperationResult(savedRoom, roomResponse);
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
            return new RoomOperationResult(savedRoom, null);
        }
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Room joinRoom(String roomId, String password, String name) {
        return joinRoomWithResponse(roomId, password, name).room();
    }

    public RoomOperationResult joinRoomWithResponse(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return new RoomOperationResult(null, null);
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        // 이미 참여중인지 확인
        if (!room.getParticipantIds().contains(user.getId())) {
            // 채팅방 참여
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }
        
        // Publish event for room updated
        try {
            RoomResponse roomResponse = mapToRoomResponse(room, name);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
            return new RoomOperationResult(room, roomResponse);
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
            return new RoomOperationResult(room, null);
        }
    }

    public record RoomOperationResult(Room room, RoomResponse response) {
    }

    public RoomResponse mapToRoomResponse(Room room, String name) {
        if (room == null) return null;

        Map<String, User> usersById = loadUsers(List.of(room));
        User creator = room.getCreator() == null ? null : usersById.get(room.getCreator());
        if (creator == null) {
            throw new RuntimeException("Creator not found for room " + room.getId());
        }

        Set<String> participantIds = room.getParticipantIds() == null
                ? Set.of()
                : room.getParticipantIds();
        List<UserResponse> participants = participantIds.stream()
                .map(userId -> {
                    User user = usersById.get(userId);
                    if (user == null) {
                        log.warn("Participant not found: roomId={}, userId={}", room.getId(), userId);
                    }
                    return user;
                })
                .filter(Objects::nonNull)
                .map(UserResponse::from)
                .toList();

        int recentMessageCount = room.getId() == null
                ? 0
                : recentMessageCounter.countRecentMessages(room.getId());

        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .hasPassword(room.isHasPassword())
                .creator(UserResponse.from(creator))
                .participants(participants)
                .createdAtDateTime(room.getCreatedAt() != null ? room.getCreatedAt() : LocalDateTime.now())
                .isCreator(room.getCreator().equals(name))
                .recentMessageCount(recentMessageCount)
                .build();
    }

    private Map<String, User> loadUsers(List<Room> rooms) {
        Set<String> userIds = new HashSet<>();
        for (Room room : rooms) {
            if (room.getCreator() != null) {
                userIds.add(room.getCreator());
            }
            if (room.getParticipantIds() != null) {
                userIds.addAll(room.getParticipantIds());
            }
        }

        if (userIds.isEmpty()) {
            return Map.of();
        }

        return userRepository.findSummariesByIdIn(userIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, user -> user, (first, ignored) -> first));
    }

    private Map<String, User> loadCreators(List<Room> rooms) {
        Set<String> creatorIds = rooms.stream()
                .map(Room::getCreator)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (creatorIds.isEmpty()) {
            return Map.of();
        }

        return userRepository.findSummariesByIdIn(creatorIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, user -> user, (first, ignored) -> first));
    }

    private RoomResponse mapToRoomListResponse(
            Room room,
            String name,
            Map<String, User> creatorsById,
            Map<String, Integer> recentMessageCounts) {
        if (room == null) return null;

        User creator = room.getCreator() == null ? null : creatorsById.get(room.getCreator());

        Set<String> participantIds = room.getParticipantIds() == null
                ? Set.of()
                : room.getParticipantIds();

        int recentMessageCount = room.getId() == null
                ? 0
                : recentMessageCounts.getOrDefault(room.getId(), 0);

        return RoomResponse.builder()
            .id(room.getId())
            .name(room.getName() != null ? room.getName() : "제목 없음")
            .hasPassword(room.isHasPassword())
            .creator(creator != null ? UserResponse.builder()
                .id(creator.getId())
                .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                .email(creator.getEmail() != null ? creator.getEmail() : "")
                .build() : null)
            .participants(toLightweightParticipants(participantIds))
            .createdAtDateTime(room.getCreatedAt() != null ? room.getCreatedAt() : LocalDateTime.now())
            .isCreator(creator != null && Objects.equals(creator.getEmail(), name))
            .recentMessageCount(recentMessageCount)
            .build();
    }

    private List<UserResponse> toLightweightParticipants(Set<String> participantIds) {
        return participantIds.stream()
                .filter(Objects::nonNull)
                .map(userId -> UserResponse.builder().id(userId).build())
                .collect(Collectors.toList());
    }
}
