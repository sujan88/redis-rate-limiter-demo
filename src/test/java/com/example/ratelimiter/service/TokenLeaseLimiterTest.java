package com.example.ratelimiter.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenLeaseLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private void grants(Long... counts) {
        when(redis.execute(any(RedisScript.class), anyList(), eq("5"), eq("1.0"), eq("2")))
                .thenReturn(counts[0], java.util.Arrays.copyOfRange(counts, 1, counts.length));
    }
    private TokenLeaseLimiter limiter(boolean fallback, Duration ttl) {
        return new TokenLeaseLimiter(redis, metrics, 5, 1, 2, ttl, fallback);
    }
    @Test void leasesLocallyAndSupportsPartialGrantsAndDenial() {
        grants(2L, 1L, 0L);
        var limiter = limiter(false, Duration.ofSeconds(2));
        assertTrue(limiter.allow("alice", "search"));
        assertTrue(limiter.allow("alice", "search"));
        assertTrue(limiter.allow("alice", "search"));
        assertFalse(limiter.allow("alice", "search"));
        verify(redis, times(3)).execute(any(RedisScript.class), anyList(), eq("5"), eq("1.0"), eq("2"));
        assertEquals(1, metrics.get("rate_limit.allowed").tag("source", "lease").counter().count());
    }
    @Test void separatesServicesAndExpiresUnusedTokens() throws Exception {
        grants(2L);
        var limiter = limiter(false, Duration.ofMillis(20));
        limiter.allow("alice", "search");
        limiter.allow("alice", "booking");
        Thread.sleep(50);
        limiter.allow("alice", "search");
        verify(redis, times(3)).execute(any(RedisScript.class), anyList(), eq("5"), eq("1.0"), eq("2"));
    }
    @Test void concurrentRequestsCannotOverspendLease() throws Exception {
        grants(2L, 0L);
        var limiter = limiter(false, Duration.ofSeconds(10));
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = IntStream.range(0, 20)
                    .mapToObj(i -> (Callable<Boolean>) () -> limiter.allow("alice", "search")).toList();
            long allowed = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get()) allowed++;
            assertEquals(2, allowed);
        } finally { pool.shutdownNow(); }
    }
    @Test void failClosedIsDefaultAndCounted() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("5"), eq("1.0"), eq("2")))
                .thenThrow(new RedisConnectionFailureException("down"));
        var limiter = limiter(false, Duration.ofSeconds(2));
        assertThrows(RedisConnectionFailureException.class, () -> limiter.allow("alice", "search"));
        assertEquals(1, metrics.get("rate_limit.redis_error").counter().count());
        assertEquals(1, metrics.get("rate_limit.denied").tag("source", "fail_closed").counter().count());
        assertEquals(0, metrics.get("rate_limit.fallback_active").gauge().value());
    }
    @Test void fallbackIsSeparateAndSuccessfulProbeClearsGauge() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), eq("5"), eq("1.0"), eq("2")))
                .thenReturn(2L).thenThrow(new RedisConnectionFailureException("down"))
                .thenThrow(new RedisConnectionFailureException("down")).thenReturn(0L);
        var limiter = limiter(true, Duration.ofSeconds(2));
        assertTrue(limiter.allow("alice", "search"));
        assertTrue(limiter.allow("alice", "search")); // Reserved token works without Redis.
        assertFalse(limiter.allow("alice", "search")); // Separate fallback starts empty.
        assertEquals(1, metrics.get("rate_limit.fallback_active").gauge().value());
        Thread.sleep(1100);
        assertTrue(limiter.allow("alice", "search"));
        assertFalse(limiter.allow("alice", "search")); // Redis denial must not use fallback.
        assertEquals(0, metrics.get("rate_limit.fallback_active").gauge().value());
        assertEquals(1, metrics.get("rate_limit.allowed").tag("source", "fallback").counter().count());
    }
}
