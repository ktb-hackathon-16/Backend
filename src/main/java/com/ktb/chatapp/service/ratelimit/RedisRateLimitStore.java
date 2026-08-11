package com.ktb.chatapp.service.ratelimit;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed atomic rate limit store.
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimitStore implements AtomicRateLimitStore {

    private static final String KEY_PREFIX = "chatapp:rate-limit:";
    private static final RedisScript<List> CHECK_AND_INCREMENT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local maxRequests = tonumber(ARGV[1])
            local windowSeconds = tonumber(ARGV[2])
            local current = tonumber(redis.call('GET', key) or '0')

            if current >= maxRequests then
                return {0, current, redis.call('TTL', key)}
            end

            current = redis.call('INCR', key)
            if current == 1 then
                redis.call('EXPIRE', key, windowSeconds)
            end

            return {1, current, redis.call('TTL', key)}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public RateLimitDecision checkAndIncrement(
            String clientId,
            int maxRequests,
            Duration window) {
        long windowSeconds = Math.max(1L, window.getSeconds());
        List<?> result = redisTemplate.execute(
                CHECK_AND_INCREMENT,
                List.of(KEY_PREFIX + clientId),
                String.valueOf(maxRequests),
                String.valueOf(windowSeconds));

        if (result == null || result.size() != 3) {
            throw new IllegalStateException("Redis rate limit result is invalid");
        }

        return new RateLimitDecision(
                number(result.get(0)).intValue() == 1,
                number(result.get(1)).intValue(),
                number(result.get(2)).longValue());
    }

    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return Long.valueOf(String.valueOf(value));
    }
}
