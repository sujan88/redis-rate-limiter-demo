package com.example.ratelimiter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class TokenLeaseLimiter {
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final int capacity, leaseSize;
    private final double refill;
    private final boolean localFallback;
    private final Cache<String, Lease> leases;
    // Independent state: these tokens have NEVER been reserved in Redis.
    private final Cache<String, FallbackBucket> fallback = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterAccess(Duration.ofMinutes(1)).build();
    private final Object[] locks = new Object[256];
    private final AtomicInteger fallbackActive = new AtomicInteger();
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

    public TokenLeaseLimiter(StringRedisTemplate redis, MeterRegistry metrics,
            @Value("${rate-limit.capacity:5}") int capacity,
            @Value("${rate-limit.refill-per-second:1}") double refill,
            @Value("${rate-limit.lease-size:2}") int leaseSize,
            @Value("${rate-limit.lease-ttl:2s}") Duration ttl,
            @Value("${rate-limit.local-fallback:false}") boolean localFallback) {
        if (capacity < 1 || leaseSize < 1 || !Double.isFinite(refill) || refill <= 0
                || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Invalid token lease settings");
        this.redis = redis;
        this.metrics = metrics;
        this.capacity = capacity;
        this.refill = refill;
        this.leaseSize = Math.min(leaseSize, capacity);
        this.localFallback = localFallback;
        leases = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(ttl).build();
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        script.setLocation(new ClassPathResource("lua/token-lease.lua"));
        script.setResultType(Long.class);
        metrics.gauge("rate_limit.fallback_active", List.of(
                io.micrometer.core.instrument.Tag.of("technique", "token-bucket")), fallbackActive);
        // Register zero values so these metrics are inspectable before the first request.
        for (String source : List.of("lease", "redis", "fallback", "fail_closed")) {
            metrics.counter("rate_limit.allowed", "technique", "token-bucket", "source", source);
            metrics.counter("rate_limit.denied", "technique", "token-bucket", "source", source);
        }
        metrics.counter("rate_limit.redis_error", "technique", "token-bucket");
    }

    public boolean allow(String customer, String service) {
        String key = customer + ":" + service;
        // Stable striped locks prevent duplicate reservations and local overspending.
        // Lookup happens INSIDE the lock, including when Caffeine evicts an entry.
        synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
            Lease lease = leases.getIfPresent(key);
            if (lease != null && lease.remaining > 0) {
                lease.remaining--;
                return record(true, "lease");
            }
            try {
                Long granted = redis.execute(script, List.of("demo:rl:token-lease:" + key),
                        Integer.toString(capacity), Double.toString(refill), Integer.toString(leaseSize));
                if (granted == null) throw new org.springframework.dao.DataRetrievalFailureException("Missing lease result");
                fallbackActive.set(0); // A successful probe, even a zero grant, ends observed fallback mode.
                fallback.invalidate(key);
                if (granted == 0) return record(false, "redis");
                leases.put(key, new Lease(granted.intValue() - 1));
                return record(true, "redis");
            } catch (DataAccessException ex) {
                metrics.counter("rate_limit.redis_error", "technique", "token-bucket").increment();
                if (!localFallback) {
                    record(false, "fail_closed");
                    throw ex;
                }
                fallbackActive.set(1);
                // Retry Redis on each cache miss; no circuit breaker in this study demo.
                FallbackBucket bucket = fallback.get(key, ignored -> new FallbackBucket());
                long now = System.nanoTime();
                bucket.tokens = Math.min(5, bucket.tokens + (now - bucket.last) / 1_000_000_000.0);
                bucket.last = now;
                boolean allowed = bucket.tokens >= 1;
                if (allowed) bucket.tokens--;
                return record(allowed, "fallback");
            }
        }
    }

    private boolean record(boolean allowed, String source) {
        metrics.counter(allowed ? "rate_limit.allowed" : "rate_limit.denied",
                "technique", "token-bucket", "source", source).increment();
        return allowed;
    }

    private static class Lease {
        int remaining;
        Lease(int remaining) { this.remaining = remaining; }
    }

    private static class FallbackBucket {
        // Start empty: cache eviction or recovery must not mint a fresh fallback burst.
        double tokens;
        long last = System.nanoTime();
    }
}
