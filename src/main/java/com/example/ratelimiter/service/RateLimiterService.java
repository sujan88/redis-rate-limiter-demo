package com.example.ratelimiter.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterService {
    // Windows: 5 accepted requests / 10 seconds. Buckets: capacity 5, rate 1/sec.
    private static final String LIMIT = "5", WINDOW_MS = "10000", RATE = "1";
    private final StringRedisTemplate redis;
    private final Map<String, DefaultRedisScript<Long>> scripts;

    public RateLimiterService(StringRedisTemplate redis) {
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

    public boolean allow(String technique, String client) {
        var script = scripts.get(technique);
        if (script == null) throw new IllegalArgumentException("Unknown technique. Use: " + scripts.keySet());
        // Separate state per client AND technique; one atomic round trip per request.
        Long result = redis.execute(script, List.of("demo:rl:" + technique + ":" + client),
                LIMIT, WINDOW_MS, RATE, UUID.randomUUID().toString());
        return Long.valueOf(1).equals(result);
    }
}
