package com.ktb.chatapp.service.ratelimit;

public record RateLimitDecision(
        boolean allowed,
        int count,
        long ttlSeconds) {
}
