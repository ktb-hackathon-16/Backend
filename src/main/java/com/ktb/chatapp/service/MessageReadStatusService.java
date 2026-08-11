package com.ktb.chatapp.service;

import com.ktb.chatapp.model.ReadReceipt;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            // 워터마크가 "전진"할 때만 갱신되도록 조건을 건다.
            // 네트워크 지연 등으로 read-ack가 순서 뒤바뀌어 늦게 도착해도,
            // 이미 반영된 더 최신 워터마크를 과거 값으로 덮어쓰지 않기 위함이다.
            Criteria notBehind = new Criteria().orOperator(
                    Criteria.where("lastReadAt").lt(lastReadAt),
                    Criteria.where("lastReadAt").exists(false));
            Query query = Query.query(new Criteria().andOperator(
                    Criteria.where("room").is(roomId),
                    Criteria.where("user").is(userId),
                    notBehind));
            Update update = new Update()
                    .set("lastReadMessageId", lastReadMessageId)
                    .set("lastReadAt", lastReadAt)
                    .setOnInsert("room", roomId)
                    .setOnInsert("user", userId);
            mongoTemplate.upsert(query, update, ReadReceipt.class);

            log.debug("Read watermark advanced for room {} by user {} to message {}",
                    roomId, userId, lastReadMessageId);
        } catch (Exception e) {
            log.error("Read status update error for room {} user {}", roomId, userId, e);
        }
    }
}
