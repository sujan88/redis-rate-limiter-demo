package com.example.ratelimiter.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Study example: a single-Redis lease, without renewal or fencing tokens. */
@Service
public class RedisDistributedLockDemo {
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public RedisDistributedLockDemo(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Lease> tryAcquire(String resource, Duration ttl) {
        if (resource == null || !resource.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("resource must be 1-128 letters, digits, _ or -");
        }
        if (ttl == null || ttl.toMillis() < 1) {
            throw new IllegalArgumentException("ttl must be at least one millisecond");
        }
        String key = "demo:distributed-lock:" + resource;
        String owner = UUID.randomUUID().toString(); // New owner for EVERY attempt.
        // One atomic SET key owner NX PX ttl. Never separate SETNX and EXPIRE:
        // a crash between them could leave a lock that never expires.
        boolean acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, ttl));
        return acquired ? Optional.of(new Lease(key, owner)) : Optional.empty();
    }

    public boolean release(Lease lease) {
        // Plain DEL is unsafe: our lease may have expired and another instance
        // may now own the key. Separate GET then DEL has the same race window.
        // Lua compares ownership and deletes atomically on the Redis server.
        return Long.valueOf(1).equals(redis.execute(UNLOCK, List.of(lease.key()), lease.owner()));
    }

    // In application code, release in finally after a successful acquisition.
    // Expiration does NOT stop Java work: a paused owner can outlive its lease.
    // Safe unlock protects the lock key, not external writes by a stale owner.
    public record Lease(String key, String owner) {}
}
