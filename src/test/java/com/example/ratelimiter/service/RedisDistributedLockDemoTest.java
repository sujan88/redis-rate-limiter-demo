package com.example.ratelimiter.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real Redis required; run with -Dredis.lock.integration=true. */
@EnabledIfSystemProperty(named = "redis.lock.integration", matches = "true")
class RedisDistributedLockDemoTest {
    private LettuceConnectionFactory connectionA, connectionB;
    private StringRedisTemplate redis;
    private RedisDistributedLockDemo instanceA, instanceB;
    private final String resource = "test-" + UUID.randomUUID();

    @BeforeEach void connect() {
        connectionA = connection();
        connectionB = connection();
        redis = new StringRedisTemplate(connectionA);
        instanceA = new RedisDistributedLockDemo(redis);
        instanceB = new RedisDistributedLockDemo(new StringRedisTemplate(connectionB));
    }

    private LettuceConnectionFactory connection() {
        var factory = new LettuceConnectionFactory(
                System.getProperty("redis.host", "localhost"), Integer.getInteger("redis.port", 6379));
        factory.afterPropertiesSet();
        return factory;
    }

    @AfterEach void disconnect() {
        // No FLUSHDB or broad cleanup: every test uses a unique expiring key.
        if (connectionA != null) connectionA.destroy();
        if (connectionB != null) connectionB.destroy();
    }

    @Test void anotherInstanceCannotAcquireUntilOwnerReleases() {
        var leaseA = instanceA.tryAcquire(resource, Duration.ofSeconds(30)).orElseThrow();
        try {
            assertTrue(redis.getExpire(leaseA.key(), TimeUnit.MILLISECONDS) > 0);
            assertTrue(instanceB.tryAcquire(resource, Duration.ofSeconds(30)).isEmpty());
            assertFalse(instanceB.release(new RedisDistributedLockDemo.Lease(leaseA.key(), "wrong-owner")));
            assertEquals(leaseA.owner(), redis.opsForValue().get(leaseA.key()));
        } finally {
            assertTrue(instanceA.release(leaseA));
        }
        var leaseB = instanceB.tryAcquire(resource, Duration.ofSeconds(30)).orElseThrow();
        try {
            assertNotEquals(leaseA.owner(), leaseB.owner());
        } finally {
            assertTrue(instanceB.release(leaseB));
        }
        assertFalse(instanceB.release(leaseB)); // Repeated unlock is harmless.
    }

    @Test void expiredOwnerCannotDeleteNewOwnersLock() {
        var leaseA = instanceA.tryAcquire(resource, Duration.ofMillis(200)).orElseThrow();
        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(leaseA.key())));
        var leaseB = instanceB.tryAcquire(resource, Duration.ofSeconds(30)).orElseThrow();
        try {
            // A resumes after a pause. Plain DEL here would delete B's lock!
            assertFalse(instanceA.release(leaseA));
            assertEquals(leaseB.owner(), redis.opsForValue().get(leaseB.key()));
            assertTrue(instanceA.tryAcquire(resource, Duration.ofSeconds(30)).isEmpty());
        } finally {
            assertTrue(instanceB.release(leaseB));
        }
    }
}
