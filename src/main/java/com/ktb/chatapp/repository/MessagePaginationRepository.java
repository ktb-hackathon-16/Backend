package com.ktb.chatapp.repository;

import com.ktb.chatapp.dto.MessageCursor;
import com.ktb.chatapp.model.Message;
import java.util.List;

/**
 * 메시지 복합 keyset pagination 조회 Repository.
 */
public interface MessagePaginationRepository {

    /**
     * 지정한 커서보다 오래된 메시지를 조회한다.
     *
     * @param roomId 조회할 채팅방 ID
     * @param cursor 조회 시작 위치. 최초 조회일 때는 null
     * @param fetchSize 실제 DB 조회 개수. limit + 1을 전달한다.
     * @return timestamp DESC, id DESC 순서의 메시지 목록
     */
    List<Message> findOlderMessages(
            String roomId,
            MessageCursor cursor,
            int fetchSize
    );
}