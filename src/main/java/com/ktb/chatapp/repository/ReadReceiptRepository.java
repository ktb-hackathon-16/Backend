package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.ReadReceipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * [NEW FILE] repository/ReadReceiptRepository.java
 *
 * ReadReceipt(방+유저별 읽음 워터마크) 컬렉션 접근용 리포지토리.
 * 실제 갱신은 MessageReadStatusService에서 MongoTemplate upsert로 처리하고,
 * 여기서는 방 입장 시 참가자들의 초기 워터마크를 조회하는 용도로만 쓴다.
 */
@Repository
public interface ReadReceiptRepository extends MongoRepository<ReadReceipt, String> {

    /**
     * 방 입장(joinRoom) 시 참가자별 현재 워터마크 스냅샷을 프론트로 내려주기 위해 사용.
     */
    List<ReadReceipt> findByRoomId(String roomId);

    Optional<ReadReceipt> findByRoomIdAndUserId(String roomId, String userId);
}
