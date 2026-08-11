package com.ktb.chatapp.service;

import com.ktb.chatapp.model.ReadReceipt;
import com.mongodb.client.result.UpdateResult;
import java.time.LocalDateTime;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageReadStatusService 단위 테스트")
class MessageReadStatusServiceUnitTest {

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String MESSAGE_ID = "message-1";
    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 8, 11, 12, 0);

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private MessageReadStatusService messageReadStatusService;

    @Test
    @DisplayName("기존 워터마크가 전진하면 insert 없이 update만 수행한다")
    void updateReadStatus_WhenExistingWatermarkCanAdvance_UpdatesOnly() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ReadReceipt.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        messageReadStatusService.updateReadStatus(ROOM_ID, USER_ID, MESSAGE_ID, READ_AT);

        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(ReadReceipt.class));
        verify(mongoTemplate, never()).upsert(any(Query.class), any(Update.class), eq(ReadReceipt.class));
    }

    @Test
    @DisplayName("초기 insert는 room+user 기준 upsert만 사용해 중복 문서 경로를 막는다")
    void updateReadStatus_WhenNoReceiptExists_UpsertsByRoomAndUserOnly() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ReadReceipt.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(ReadReceipt.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        messageReadStatusService.updateReadStatus(ROOM_ID, USER_ID, MESSAGE_ID, READ_AT);

        ArgumentCaptor<Query> upsertQueryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> upsertUpdateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(
                upsertQueryCaptor.capture(),
                upsertUpdateCaptor.capture(),
                eq(ReadReceipt.class));

        Document upsertQuery = upsertQueryCaptor.getValue().getQueryObject();
        assertThat(upsertQuery).containsEntry("room", ROOM_ID);
        assertThat(upsertQuery).containsEntry("user", USER_ID);
        assertThat(upsertQuery.toJson()).doesNotContain("lastReadAt");

        Document update = upsertUpdateCaptor.getValue().getUpdateObject();
        assertThat((Document) update.get("$setOnInsert"))
                .containsEntry("room", ROOM_ID)
                .containsEntry("user", USER_ID)
                .containsEntry("lastReadMessageId", MESSAGE_ID)
                .containsEntry("lastReadAt", READ_AT);
    }

    @Test
    @DisplayName("동시 insert 충돌이 나면 forward-only update를 한 번 더 시도한다")
    void updateReadStatus_WhenConcurrentInsertWins_RetriesForwardOnlyUpdate() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ReadReceipt.class)))
                .thenReturn(
                        UpdateResult.acknowledged(0, 0L, null),
                        UpdateResult.acknowledged(1, 1L, null));
        when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(ReadReceipt.class)))
                .thenThrow(new DuplicateKeyException("duplicate room_user_idx"));

        messageReadStatusService.updateReadStatus(ROOM_ID, USER_ID, MESSAGE_ID, READ_AT);

        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class), eq(ReadReceipt.class));
        verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(ReadReceipt.class));
    }
}
