package com.ktb.chatapp.model;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * [NEW FILE] model/ReadReceipt.java
 *
 * "Last Read Watermark 방식" 읽음 처리 아키텍처의 핵심 모델.
 * 기존 Message.readers(메시지마다 읽은 사람 배열을 저장)를 대체한다.
 *
 * 메시지 1건마다 읽은 사람 목록을 기록하는 대신, "방(room) + 유저(user)"
 * 조합당 문서 1개만 두고 "가장 마지막으로 읽은 메시지"만 갱신한다.
 * 유저가 메시지를 100개 읽든 1개 읽든 이 컬렉션에는 항상 문서 1개만 존재한다.
 *
 * (room, user) 유니크 복합 인덱스가 이 설계의 핵심이다 — 이게 있어야
 * upsert 한 번으로 "그 방에서 그 유저의 워터마크"가 항상 유일하게 유지된다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "read_receipts")
@CompoundIndex(name = "room_user_idx", def = "{'room': 1, 'user': 1}", unique = true)
public class ReadReceipt {

    @Id
    private String id;

    @Field("room")
    private String roomId;

    @Field("user")
    private String userId;

    // 가장 마지막으로 읽은 메시지 ID
    private String lastReadMessageId;

    // 가장 마지막으로 읽은 시각 - 메시지 timestamp와 비교해 안읽음 여부를 계산하는 기준
    private LocalDateTime lastReadAt;

    /**
     * [ADDED] lastReadAt을 epoch millis로 변환.
     * 프론트에 전송할 때 JSON 직렬화를 일관되게 하기 위해 사용.
     * Message.toTimestampMillis()와 같은 방식으로 구현.
     */
    public long toLastReadAtMillis() {
        if (lastReadAt == null) {
            return 0L;
        }
        return lastReadAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
