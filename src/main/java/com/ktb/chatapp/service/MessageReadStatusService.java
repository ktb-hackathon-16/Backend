package com.ktb.chatapp.service;

import com.ktb.chatapp.model.ReadReceipt;
import com.mongodb.client.result.UpdateResult;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * [CHANGED] service/MessageReadStatusService.java — 완전히 재작성.
 *
 * 변경 이력:
 * 1단계(이전 대화): messageId마다 find+save를 반복하던 for문 -> MongoTemplate
 *    updateMulti($addToSet)로 N개 문서를 벌크 업데이트 (DB 왕복 1번).
 * 2단계(지금, Last Read Watermark 방식): 메시지 하나하나에 readers 배열을 유지하는
 *    설계 자체를 버리고, "방(room)+유저(user)"당 문서 1개(ReadReceipt)에
 *    "가장 마지막으로 읽은 메시지"만 저장한다. messageIds가 1개든 100개든 항상
 *    upsert 1번으로 끝난다 — N에 대한 의존이 완전히 사라진다.
 *
 * 참고: model/ReadReceipt.java, dto/MarkAsReadRequest.java,
 *       websocket/socketio/handler/MessageReadHandler.java
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;

    /**
     * 방(roomId)에서 유저(userId)의 읽음 워터마크를 lastReadMessageId/lastReadAt까지 전진시킨다.
     *
     * @param roomId            읽음 상태를 갱신할 방 ID
     * @param userId            읽은 사용자 ID
     * @param lastReadMessageId 그 방에서 유저가 확인한 가장 마지막(최신) 메시지 ID
     * @param lastReadAt        그 메시지의 timestamp (워터마크 비교 기준)
     */
    public void updateReadStatus(
            String roomId,
            String userId,
            String lastReadMessageId,
            LocalDateTime lastReadAt) {
        if (roomId == null || roomId.isBlank()
                || userId == null || userId.isBlank()
                || lastReadMessageId == null || lastReadMessageId.isBlank()
                || lastReadAt == null) {
            return;
        }

        try {
            UpdateResult advanced = advanceExistingWatermark(roomId, userId, lastReadMessageId, lastReadAt);
            if (advanced.getMatchedCount() == 0) {
                try {
                    insertInitialWatermarkIfAbsent(roomId, userId, lastReadMessageId, lastReadAt);
                } catch (DuplicateKeyException duplicateKeyException) {
                    // A concurrent read-ack created the room+user receipt first. Retry the forward-only
                    // update so this event can still advance the watermark if it is newer.
                    advanceExistingWatermark(roomId, userId, lastReadMessageId, lastReadAt);
                }
            }

            log.debug("Read watermark advanced for room {} by user {} to message {}",
                    roomId, userId, lastReadMessageId);
        } catch (Exception e) {
            log.error("Read status update error for room {} user {}", roomId, userId, e);
        }
    }

    private UpdateResult advanceExistingWatermark(
            String roomId,
            String userId,
            String lastReadMessageId,
            LocalDateTime lastReadAt) {
        Criteria watermarkCanAdvance = new Criteria().orOperator(
                Criteria.where("lastReadAt").lt(lastReadAt),
                Criteria.where("lastReadAt").exists(false));
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("room").is(roomId),
                Criteria.where("user").is(userId),
                watermarkCanAdvance));
        Update update = new Update()
                .set("lastReadMessageId", lastReadMessageId)
                .set("lastReadAt", lastReadAt);

        return mongoTemplate.updateFirst(query, update, ReadReceipt.class);
    }

    private UpdateResult insertInitialWatermarkIfAbsent(
            String roomId,
            String userId,
            String lastReadMessageId,
            LocalDateTime lastReadAt) {
        Query query = Query.query(Criteria.where("room").is(roomId).and("user").is(userId));
        Update update = new Update()
                .setOnInsert("room", roomId)
                .setOnInsert("user", userId)
                .setOnInsert("lastReadMessageId", lastReadMessageId)
                .setOnInsert("lastReadAt", lastReadAt);

        return mongoTemplate.upsert(query, update, ReadReceipt.class);
    }
}
