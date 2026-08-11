package com.ktb.chatapp.repository;

import com.ktb.chatapp.dto.MessageCursor;
import com.ktb.chatapp.model.Message;
import java.util.List;

/**
 * 메시지 복합 keyset pagination 조회 Repository.
 */
public interface MessagePaginationRepository {
    List<Message> findOlderMessages(
            String roomId,
            MessageCursor cursor,
            int fetchSize
    );
}
