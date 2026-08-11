package com.ktb.chatapp.repository;

import com.ktb.chatapp.dto.MessageCursor;
import com.ktb.chatapp.model.Message;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * MongoDB 기반 메시지 복합 keyset pagination 구현체.
 */
@RequiredArgsConstructor
public class MessagePaginationRepositoryImpl
        implements MessagePaginationRepository {

    private final MongoOperations mongoOperations;

    @Override
    public List<Message> findOlderMessages(
            String roomId,
            MessageCursor cursor,
            int fetchSize
    ) {
        validateArguments(roomId, cursor, fetchSize);

        Query query = Query.query(
                Criteria.where("roomId").is(roomId)
        );

        if (cursor != null) {
            LocalDateTime cursorTimestamp =
                    LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(cursor.timestamp()),
                            ZoneId.systemDefault()
                    );

            Criteria olderThanCursor = new Criteria().orOperator(
                    Criteria.where("timestamp")
                            .lt(cursorTimestamp),

                    Criteria.where("timestamp")
                            .is(cursorTimestamp)
                            .and("id")
                            .lt(cursor.messageId())
            );

            query.addCriteria(olderThanCursor);
        }

        query.with(
                Sort.by(
                        Sort.Order.desc("timestamp"),
                        Sort.Order.desc("id")
                )
        );

        query.limit(fetchSize);

        return mongoOperations.find(query, Message.class);
    }

    private void validateArguments(
            String roomId,
            MessageCursor cursor,
            int fetchSize
    ) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException(
                    "roomId는 필수입니다."
            );
        }

        if (cursor != null && !cursor.isValid()) {
            throw new IllegalArgumentException(
                    "유효하지 않은 메시지 커서입니다."
            );
        }

        if (fetchSize < 1) {
            throw new IllegalArgumentException(
                    "fetchSize는 1 이상이어야 합니다."
            );
        }
    }
}