package com.example.ratelimiter.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;

@Service
public class RateLimiterService {
    // Windows: 5 accepted requests / 10 seconds. Buckets: capacity 5, rate 1/sec.
    private static final String LIMIT = "5", WINDOW_MS = "10000", RATE = "1";
    private final StringRedisTemplate redis;
    private final Map<String, DefaultRedisScript<Long>> scripts;

    private final TokenLeaseLimiter leases;
    private final MeterRegistry metrics;

    public RateLimiterService(StringRedisTemplate redis, TokenLeaseLimiter leases, MeterRegistry metrics) {
        this.leases = leases;
        this.metrics = metrics;
        this.redis = redis;
        scripts = List.of("fixed-window", "sliding-log", "sliding-counter",
                "token-bucket", "leaky-bucket").stream().collect(Collectors.toMap(
                name -> name, name -> {
                    var script = new DefaultRedisScript<Long>();
                    script.setLocation(new ClassPathResource("lua/" + name + ".lua"));
                    script.setResultType(Long.class);
                    return script;
                }));
    }

    public boolean allow(String technique, String client, String service) {
        var script = scripts.get(technique);
        if (script == null) throw new IllegalArgumentException("Unknown technique. Use: " + scripts.keySet());
        if (technique.equals("token-bucket")) return leases.allow(client, service);
        try {
            Long result = redis.execute(script, List.of("demo:rl:" + technique + ":" + client + ":" + service),
                    LIMIT, WINDOW_MS, RATE, UUID.randomUUID().toString());
            boolean allowed = Long.valueOf(1).equals(result);
            metrics.counter(allowed ? "rate_limit.allowed" : "rate_limit.denied",
                    "technique", technique, "source", "redis").increment();
            return allowed;
        } catch (DataAccessException ex) {
            metrics.counter("rate_limit.redis_error", "technique", technique).increment();
            metrics.counter("rate_limit.denied", "technique", technique, "source", "fail_closed").increment();
            throw ex;
        }
    }
}
