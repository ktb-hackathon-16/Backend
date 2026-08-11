package com.ktb.chatapp.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FetchMessagesResponse {

    private List<MessageResponse> messages;

    private boolean hasMore;

    /**
     * 다음 페이지 조회에 사용할 복합 keyset cursor.
     * 마지막 페이지이거나 기존 before 방식 응답이면 null일 수 있다.
     */
    private MessageCursor nextCursor;

    /**
     * 기존 timestamp 기반 통합 테스트를 위한 임시 하위 호환 메서드.
     * cursor 전환이 끝난 뒤 제거한다.
     */
    public long firstMessageTimestamp() {
        return messages.getFirst().getTimestamp();
    }
}