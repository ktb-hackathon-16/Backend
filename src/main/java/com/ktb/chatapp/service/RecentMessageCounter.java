package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@Slf4j
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);
    private static final Duration WARMUP_KEY_TTL = RECENT_WINDOW.plusMinutes(5);
    private static final String KEY_PREFIX = "chatapp:room:recent-messages:";
    private static final String WARMUP_READY_KEY = KEY_PREFIX + "warmup:v1:ready";
    private static final String WARMUP_LOCK_KEY = KEY_PREFIX + "warmup:v1:lock";
    private static final RedisScript<Long> RECORD_RECENT_MESSAGE = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local messageId = ARGV[1]
            local score = tonumber(ARGV[2])
            local cutoff = tonumber(ARGV[3])
            local ttlSeconds = tonumber(ARGV[4])

            redis.call('ZADD', key, 'NX', score, messageId)
            redis.call('ZREMRANGEBYSCORE', key, '-inf', '(' .. cutoff)
            redis.call('EXPIRE', key, ttlSeconds)
            return 1
            """, Long.class);

    private final MessageRepository messageRepository;
    private final StringRedisTemplate redisTemplate;
    private final RecentMessageCache recentMessageCache;

    @Autowired
    public RecentMessageCounter(
            MessageRepository messageRepository,
            StringRedisTemplate redisTemplate,
            RecentMessageCache recentMessageCache) {
        this.messageRepository = messageRepository;
        this.redisTemplate = redisTemplate;
        this.recentMessageCache = recentMessageCache;
    }

    public RecentMessageCounter(
            MessageRepository messageRepository,
            StringRedisTemplate redisTemplate) {
        this(messageRepository, redisTemplate, null);
    }

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        try {
            if (isWarmupReady()) {
                return countFromRedis(roomId, since);
            }
        } catch (Exception e) {
            log.warn("recent message Redis read skipped for roomId={}", roomId, e);
        }
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    public Map<String, Integer> countRecentMessagesByRoomIds(List<String> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }

        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        try {
            if (isWarmupReady()) {
                return countFromRedis(roomIds, since);
            }
        } catch (Exception e) {
            log.warn("recent message Redis batch read skipped", e);
        }
        return countFromMongo(roomIds, since);
    }

    public void recordMessage(Message message) {
        if (message == null || message.getId() == null
                || message.getRoomId() == null || message.getTimestamp() == null) {
            return;
        }

        try {
            if (recentMessageCache != null) {
                recentMessageCache.put(message.getRoomId(), List.of(message));
            }

            LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
            redisTemplate.execute(
                    RECORD_RECENT_MESSAGE,
                    List.of(key(message.getRoomId())),
                    message.getId(),
                    String.valueOf(toEpochMillis(message.getTimestamp())),
                    String.valueOf(toEpochMillis(since)),
                    String.valueOf(WARMUP_KEY_TTL.getSeconds()));
        } catch (Exception e) {
            log.warn("recent message Redis record skipped for roomId={}", message.getRoomId(), e);
        }
    }

    public void warmupRecentMessagesByRoomIds(List<String> roomIds) {
        if (roomIds.isEmpty()) {
            return;
        }

        boolean lockAcquired = false;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(WARMUP_READY_KEY))) {
                return;
            }

            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(WARMUP_LOCK_KEY, "1", Duration.ofMinutes(1));
            if (!Boolean.TRUE.equals(acquired)) {
                return;
            }
            lockAcquired = true;

            if (Boolean.TRUE.equals(redisTemplate.hasKey(WARMUP_READY_KEY))) {
                return;
            }

            LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
            List<MessageRepository.RecentMessageSeed> seeds =
                    messageRepository.findRecentMessageSeedsByRoomIds(roomIds, since);
            writeSeeds(seeds);
            redisTemplate.opsForValue().set(WARMUP_READY_KEY, "1", WARMUP_KEY_TTL);
        } catch (Exception e) {
            log.warn("recent message Redis warm-up skipped", e);
        } finally {
            if (lockAcquired) {
                try {
                    redisTemplate.delete(WARMUP_LOCK_KEY);
                } catch (Exception e) {
                    log.debug("recent message Redis warm-up lock cleanup skipped", e);
                }
            }
        }
    }

    private void writeSeeds(List<MessageRepository.RecentMessageSeed> seeds) {
        if (seeds.isEmpty()) {
            return;
        }

        Set<String> roomKeys = seeds.stream()
                .map(MessageRepository.RecentMessageSeed::getRoom)
                .map(this::key)
                .collect(Collectors.toSet());
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (MessageRepository.RecentMessageSeed seed : seeds) {
                byte[] roomKey = serialize(key(seed.getRoom()));
                byte[] messageId = serialize(seed.getId());
                connection.zAdd(
                        roomKey,
                        toEpochMillis(seed.getTimestamp()),
                        messageId,
                        RedisZSetCommands.ZAddArgs.ifNotExists());
            }
            for (String roomKey : roomKeys) {
                connection.expire(serialize(roomKey), WARMUP_KEY_TTL.getSeconds());
            }
            return null;
        });
    }

    private boolean isWarmupReady() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(WARMUP_READY_KEY));
    }

    private int countFromRedis(String roomId, LocalDateTime since) {
        Long count = redisTemplate.opsForZSet()
                .count(key(roomId), toEpochMillis(since), Double.POSITIVE_INFINITY);
        return toInt(count);
    }

    private Map<String, Integer> countFromRedis(List<String> roomIds, LocalDateTime since) {
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String roomId : roomIds) {
                connection.zCount(
                        serialize(key(roomId)),
                        toEpochMillis(since),
                        Double.POSITIVE_INFINITY);
            }
            return null;
        });

        if (results.size() != roomIds.size()) {
            throw new IllegalStateException("Redis recent message count result is invalid");
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < roomIds.size(); i++) {
            counts.put(roomIds.get(i), toInt(results.get(i)));
        }
        return counts;
    }

    private Map<String, Integer> countFromMongo(List<String> roomIds, LocalDateTime since) {
        return messageRepository.countRecentMessagesByRoomIds(roomIds, since).stream()
                .filter(count -> count.getRoom() != null)
                .collect(Collectors.toMap(
                        MessageRepository.RecentMessageCount::getRoom,
                        count -> (int) count.getCount(),
                        Integer::sum));
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof byte[] bytes) {
            return Integer.parseInt(new String(bytes, StandardCharsets.UTF_8));
        }
        if (value == null) {
            throw new IllegalStateException("Redis recent message count is null");
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private byte[] serialize(String value) {
        return redisTemplate.getStringSerializer().serialize(value);
    }

    private long toEpochMillis(LocalDateTime timestamp) {
        return timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String key(String roomId) {
        return KEY_PREFIX + roomId;
    }
}
