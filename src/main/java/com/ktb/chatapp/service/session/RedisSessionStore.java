package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@Primary
@RequiredArgsConstructor
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "chatapp:session:";
    private static final long DEFAULT_TTL_SECONDS = 30 * 60L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Session> findByUserId(String userId) {
        String value = redisTemplate.opsForValue().get(key(userId));
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(value, Session.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("Redis session data is invalid", e);
        }
    }

    @Override
    public Session save(Session session) {
        try {
            redisTemplate.opsForValue().set(
                    key(session.getUserId()),
                    objectMapper.writeValueAsString(session),
                    ttl(session));
            return session;
        } catch (JacksonException e) {
            throw new IllegalStateException("Redis session data could not be serialized", e);
        }
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(key(userId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        Session session = findByUserId(userId).orElse(null);
        if (session != null && sessionId.equals(session.getSessionId())) {
            redisTemplate.delete(key(userId));
        }
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }

    private Duration ttl(Session session) {
        if (session.getExpiresAt() == null) {
            return Duration.ofSeconds(DEFAULT_TTL_SECONDS);
        }
        long seconds = session.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
        return Duration.ofSeconds(Math.max(1L, seconds));
    }
}
