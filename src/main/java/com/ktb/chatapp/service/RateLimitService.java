package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.AtomicRateLimitStore;
import com.ktb.chatapp.service.ratelimit.RateLimitDecision;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static java.net.InetAddress.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final AtomicRateLimitStore rateLimitStore;

    @Value("${HOSTNAME:''}")
    private String hostName;
    
    @PostConstruct
    public void init() {
        if (!hostName.isEmpty()) {
            return;
        }
        hostName = generateHostname();
    }
    
    private String generateHostname() {
        try {
            return getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }
    
    
    public RateLimitCheckResult checkRateLimit(String _clientId, int maxRequests, Duration window) {
        String actualClientId = hostName + ":" + _clientId;
        Duration requestedWindow = window != null ? window : Duration.ofSeconds(1);
        long windowSeconds = Math.max(1L, requestedWindow.getSeconds());
        Duration effectiveWindow = Duration.ofSeconds(windowSeconds);
        long nowEpochSeconds = Instant.now().getEpochSecond();

        try {
            RateLimitDecision decision = rateLimitStore.checkAndIncrement(
                    actualClientId, maxRequests, effectiveWindow);
            long ttlSeconds = Math.max(1L, decision.ttlSeconds());
            long resetEpochSeconds = nowEpochSeconds + ttlSeconds;

            if (!decision.allowed()) {
                return RateLimitCheckResult.rejected(
                        maxRequests, windowSeconds, resetEpochSeconds, ttlSeconds);
            }

            int remaining = Math.max(0, maxRequests - decision.count());
            return RateLimitCheckResult.allowed(
                    maxRequests, remaining, windowSeconds, resetEpochSeconds, ttlSeconds);
        } catch (Exception e) {
            log.error("Rate limit check failed for client: {}", actualClientId, e);
            long resetEpochSeconds = nowEpochSeconds + windowSeconds;
            return RateLimitCheckResult.allowed(
                    maxRequests, maxRequests, windowSeconds, resetEpochSeconds, windowSeconds);
        }
    }
    
}
