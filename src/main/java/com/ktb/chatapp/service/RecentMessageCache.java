package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.MessageCursor;
import com.ktb.chatapp.model.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis cache for the latest messages of each room.
 * MongoDB remains the source of truth; an incomplete cache read returns empty.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecentMessageCache {

    static final int CACHE_CAPACITY = 60;
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "chatapp:room:message-cache:";

    private static final RedisScript<Long> WRITE_MESSAGES = new DefaultRedisScript<>("""
            local indexKey = KEYS[1]
            local payloadKey = KEYS[2]
            local ttlSeconds = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])

            for i = 3, #ARGV, 2 do
                local member = ARGV[i]
                local payload = ARGV[i + 1]
                redis.call('ZADD', indexKey, 0, member)
                redis.call('HSET', payloadKey, member, payload)
            end

            local stale = redis.call('ZRANGE', indexKey, 0, -(capacity + 1))
            if #stale > 0 then
                redis.call('ZREMRANGEBYRANK', indexKey, 0, -(capacity + 1))
                for _, member in ipairs(stale) do
                    redis.call('HDEL', payloadKey, member)
                end
            end

            redis.call('EXPIRE', indexKey, ttlSeconds)
            redis.call('EXPIRE', payloadKey, ttlSeconds)
            return #stale
            """, Long.class);

    private static final RedisScript<List> READ_MESSAGES = new DefaultRedisScript<>("""
            local indexKey = KEYS[1]
            local payloadKey = KEYS[2]
            local cursorMember = ARGV[1]
            local fetchSize = tonumber(ARGV[2])
            local maximum = '+'

            if cursorMember ~= '' then
                maximum = '(' .. cursorMember
            end

            local members = redis.call(
                'ZREVRANGEBYLEX', indexKey, maximum, '-',
                'LIMIT', 0, fetchSize
            )

            if #members < fetchSize then
                return {'MISS'}
            end

            local result = {'HIT'}
            for _, member in ipairs(members) do
                local payload = redis.call('HGET', payloadKey, member)
                if not payload then
                    return {'MISS'}
                end
                table.insert(result, payload)
            end
            return result
            """, List.class);

    private static final RedisScript<Long> UPDATE_MESSAGE = new DefaultRedisScript<>("""
            local indexKey = KEYS[1]
            local payloadKey = KEYS[2]
            local member = ARGV[1]
            local payload = ARGV[2]
            local ttlSeconds = tonumber(ARGV[3])

            if not redis.call('ZSCORE', indexKey, member) then
                return 0
            end

            redis.call('HSET', payloadKey, member, payload)
            redis.call('EXPIRE', indexKey, ttlSeconds)
            redis.call('EXPIRE', payloadKey, ttlSeconds)
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<List<Message>> findOlderMessages(
            String roomId,
            MessageCursor cursor,
            int fetchSize
    ) {
        if (roomId == null || roomId.isBlank() || fetchSize < 1
                || (cursor != null && !cursor.isValid())) {
            return Optional.empty();
        }

        try {
            List<?> result = redisTemplate.execute(
                    READ_MESSAGES,
                    List.of(indexKey(roomId), payloadKey(roomId)),
                    cursor == null ? "" : member(cursor),
                    String.valueOf(fetchSize)
            );

            if (result == null || result.size() < 2
                    || !"HIT".equals(stringValue(result.getFirst()))) {
                return Optional.empty();
            }

            List<Message> messages = new ArrayList<>(result.size() - 1);
            for (int i = 1; i < result.size(); i++) {
                messages.add(objectMapper.readValue(stringValue(result.get(i)), Message.class));
            }
            return Optional.of(messages);
        } catch (Exception e) {
            log.debug("recent message cache read skipped for roomId={}", roomId, e);
            return Optional.empty();
        }
    }

    public void put(String roomId, List<Message> messages) {
        if (roomId == null || roomId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }

        List<String> arguments = new ArrayList<>(2 + messages.size() * 2);
        arguments.add(String.valueOf(CACHE_TTL.getSeconds()));
        arguments.add(String.valueOf(CACHE_CAPACITY));

        try {
            for (Message message : messages) {
                if (message == null || message.getId() == null || message.getTimestamp() == null) {
                    continue;
                }
                arguments.add(member(message));
                arguments.add(objectMapper.writeValueAsString(message));
            }

            if (arguments.size() == 2) {
                return;
            }

            redisTemplate.execute(
                    WRITE_MESSAGES,
                    List.of(indexKey(roomId), payloadKey(roomId)),
                    arguments.toArray()
            );
        } catch (Exception e) {
            log.debug("recent message cache write skipped for roomId={}", roomId, e);
        }
    }

    public void update(Message message) {
        if (message == null || message.getRoomId() == null || message.getId() == null
                || message.getTimestamp() == null) {
            return;
        }

        try {
            redisTemplate.execute(
                    UPDATE_MESSAGE,
                    List.of(indexKey(message.getRoomId()), payloadKey(message.getRoomId())),
                    member(message),
                    objectMapper.writeValueAsString(message),
                    String.valueOf(CACHE_TTL.getSeconds())
            );
        } catch (Exception e) {
            log.debug("recent message cache update skipped for roomId={}", message.getRoomId(), e);
        }
    }

    public void invalidate(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(List.of(indexKey(roomId), payloadKey(roomId)));
        } catch (Exception e) {
            log.debug("recent message cache invalidation skipped for roomId={}", roomId, e);
        }
    }

    private String member(Message message) {
        return member(message.toTimestampMillis(), message.getId());
    }

    private String member(MessageCursor cursor) {
        return member(cursor.timestamp(), cursor.messageId());
    }

    private String member(long timestamp, String messageId) {
        return String.format(Locale.ROOT, "%019d:%s", timestamp, messageId);
    }

    private String indexKey(String roomId) {
        return KEY_PREFIX + roomId + ":index";
    }

    private String payloadKey(String roomId) {
        return KEY_PREFIX + roomId + ":payload";
    }

    private String stringValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }
}
