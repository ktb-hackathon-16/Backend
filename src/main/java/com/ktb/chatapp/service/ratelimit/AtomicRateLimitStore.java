package com.ktb.chatapp.service.ratelimit;

import java.time.Duration;

/**
 * Atomic rate-limit operation supplied by shared stores such as Redis.
 */
public interface AtomicRateLimitStore {

    RateLimitDecision checkAndIncrement(String clientId, int maxRequests, Duration window);
}
