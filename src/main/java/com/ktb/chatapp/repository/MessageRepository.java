package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository
        extends MongoRepository<Message, String>,
        MessagePaginationRepository {

    /**
     * 기존 timestamp 기반 페이지 조회.
     *
     * MessageLoader를 신규 keyset 조회로 변경하기 전까지 유지한다.
     */
    Page<Message> findByRoomIdAndTimestampBefore(
            String roomId,
            LocalDateTime timestamp,
            Pageable pageable
    );

    /**
     * 특정 시간 이후 메시지 수 조회.
     */
    @Query(
            value = "{ 'room': ?0, 'timestamp': { $gte: ?1 } }",
            count = true
    )
    long countRecentMessagesByRoomId(
            String roomId,
            LocalDateTime since
    );

    /**
     * fileId로 메시지 조회.
     */
    Optional<Message> findByFileId(String fileId);
}