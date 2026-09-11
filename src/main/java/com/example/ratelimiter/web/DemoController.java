package com.example.ratelimiter.web;

import com.example.ratelimiter.service.RateLimiterService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private final RateLimiterService limiter;
    public DemoController(RateLimiterService limiter) { this.limiter = limiter; }

    @GetMapping("/{technique}")
    public ResponseEntity<?> demo(@PathVariable String technique,
            @RequestParam(defaultValue = "alice") String client) {
        if (!client.matches("[A-Za-z0-9_-]{1,64}")) {
            return ResponseEntity.badRequest().body(new Error("client must be 1-64 letters, digits, _ or -"));
        }
        boolean allowed = limiter.allow(technique, client);
        return ResponseEntity.status(allowed ? 200 : 429)
                .body(new Result(technique, client, allowed, allowed ? "Request accepted" : "Rate limit exceeded"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Error> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new Error(ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Error> unavailable(DataAccessException ex) {
        // Fail closed: an unavailable Redis must not silently bypass the limit.
        return ResponseEntity.status(503).body(new Error("Redis unavailable; request not processed"));
    }

    public record Result(String technique, String client, boolean allowed, String message) {}
    public record Error(String message) {}
}
