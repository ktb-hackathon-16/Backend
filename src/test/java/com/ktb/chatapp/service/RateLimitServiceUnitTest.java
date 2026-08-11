package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.AtomicRateLimitStore;
import com.ktb.chatapp.service.ratelimit.RateLimitDecision;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String HOST_NAME = "test-host";
    private static final String CLIENT_ID = "client-1";
    private static final String STORE_CLIENT_ID = HOST_NAME + ":" + CLIENT_ID;

    @Mock
    private AtomicRateLimitStore rateLimitStore;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(rateLimitStore);
        ReflectionTestUtils.setField(rateLimitService, "hostName", HOST_NAME);
    }

    @Test
    @DisplayName("최초 요청은 Redis atomic 결과를 사용하고 남은 횟수를 반환한다")
    void checkRateLimit_FirstRequest_ReturnsRemainingCount() {
        Duration window = Duration.ofSeconds(30);
        when(rateLimitStore.checkAndIncrement(STORE_CLIENT_ID, 3, window))
                .thenReturn(new RateLimitDecision(true, 1, 30));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, window);

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
        verify(rateLimitStore).checkAndIncrement(STORE_CLIENT_ID, 3, window);
    }

    @Test
    @DisplayName("Redis atomic count가 증가하면 남은 횟수가 감소한다")
    void checkRateLimit_ExistingCount_UsesAtomicResult() {
        Duration window = Duration.ofSeconds(30);
        when(rateLimitStore.checkAndIncrement(STORE_CLIENT_ID, 3, window))
                .thenReturn(new RateLimitDecision(true, 2, 20));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, window);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isEqualTo(20);
    }

    @Test
    @DisplayName("Redis atomic 결과가 거부하면 요청을 차단한다")
    void checkRateLimit_LimitReached_ReturnsRejected() {
        Duration window = Duration.ofSeconds(30);
        when(rateLimitStore.checkAndIncrement(STORE_CLIENT_ID, 3, window))
                .thenReturn(new RateLimitDecision(false, 3, 10));

        long beforeCall = Instant.now().getEpochSecond();
        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, window);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isEqualTo(10);
        assertThat(result.resetEpochSeconds()).isBetween(beforeCall + 10, beforeCall + 11);
    }

    @Test
    @DisplayName("0초 window는 1초로 정규화된다")
    void checkRateLimit_ZeroWindow_NormalizesToOneSecond() {
        Duration normalizedWindow = Duration.ofSeconds(1);
        when(rateLimitStore.checkAndIncrement(STORE_CLIENT_ID, 3, normalizedWindow))
                .thenReturn(new RateLimitDecision(true, 1, 1));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ZERO);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("null window는 1초로 정규화된다")
    void checkRateLimit_NullWindow_NormalizesToOneSecond() {
        Duration normalizedWindow = Duration.ofSeconds(1);
        when(rateLimitStore.checkAndIncrement(STORE_CLIENT_ID, 3, normalizedWindow))
                .thenReturn(new RateLimitDecision(true, 1, 1));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("Redis 저장소 오류 시 기존 fail-open 정책을 유지한다")
    void checkRateLimit_StoreFailure_FailsOpen() {
        when(rateLimitStore.checkAndIncrement(
                STORE_CLIENT_ID, 3, Duration.ofSeconds(30)))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("null clientId도 host prefix가 적용된 Redis key로 전달된다")
    void checkRateLimit_NullClientId_UsesHostPrefixedKey() {
        when(rateLimitStore.checkAndIncrement(
                HOST_NAME + ":null", 3, Duration.ofSeconds(30)))
                .thenReturn(new RateLimitDecision(true, 1, 30));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        verify(rateLimitStore).checkAndIncrement(
                HOST_NAME + ":null", 3, Duration.ofSeconds(30));
    }
}
