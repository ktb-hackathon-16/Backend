package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-backed implementation of ChatDataStore.
 *
 * LocalChatDataStore keeps UserRooms / ConnectedUsers state in a per-JVM
 * ConcurrentHashMap, which only works correctly when a single Socket.IO
 * node is running: with 2+ nodes behind a load balancer, each node has its
 * own copy of "which rooms is this user in" / "which socket belongs to this
 * user", so the answer depends on which node happens to handle the request.
 *
 * This mirrors the SessionStore -> RedisSessionStore migration: same
 * interface, same key/value shape, just backed by Redis so every node reads
 * and writes the same data. Marked @Primary so UserRooms / ConnectedUsers
 * pick this up automatically instead of the in-memory LocalChatDataStore
 * bean defined in SocketIOConfig.
 *
 * Note: unlike RedisSessionStore, entries here are not given a TTL. The
 * existing local-memory store never expired entries either - they were
 * only ever removed explicitly (UserRooms.remove/clear, ConnectedUsers.del)
 * on room leave / socket disconnect - so this keeps that same lifecycle.
 * The trade-off is that if a node is killed without running its disconnect
 * handlers (OOM kill, hard crash), its entries will linger in Redis instead
 * of disappearing with the process. That's an acceptable follow-up (e.g. a
 * periodic reconciliation job) rather than something to solve here.
 */
@Component
@Primary
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private static final String KEY_PREFIX = "chatapp:chatdata:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String value = redisTemplate.opsForValue().get(redisKey(key));
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(value, type));
        } catch (JacksonException e) {
            throw new IllegalStateException("Redis chat data is invalid for key: " + key, e);
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(redisKey(key), objectMapper.writeValueAsString(value));
        } catch (JacksonException e) {
            throw new IllegalStateException("Redis chat data could not be serialized for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(redisKey(key));
    }

    @Override
    public int size() {
        // KEYS is O(N) and blocks Redis - fine for a low-frequency metrics
        // gauge at hackathon scale, but swap for a maintained counter (or
        // SCAN) if the key count grows large enough for this to matter.
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        return keys == null ? 0 : keys.size();
    }

    private String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
