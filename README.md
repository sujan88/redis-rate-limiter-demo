# Redis rate limiter — interview demo

Java 17+, Maven 3.6.3+, Spring Boot 3.5.5, Spring Web and Spring Data Redis.
Five small Lua scripts perform atomic checks and updates in Redis. No database,
authentication, frontend, or background workers.

## Run

Start Redis (Docker required):

```sh
docker run --rm --name rate-limit-redis -p 127.0.0.1:6379:6379 redis:7-alpine
```

In another terminal, from this folder:

```sh
mvn spring-boot:run
```

Or build and run an executable JAR:

```sh
mvn clean package
java -jar target/redis-rate-limiter-demo-1.0.0.jar
```

Optional environment variables: `REDIS_HOST`, `REDIS_PORT`, `PORT` (defaults:
localhost, 6379, 8080). Stop the app with Ctrl-C; stop Redis with
`docker stop rate-limit-redis`.

## Try all five

```sh
curl -i 'http://localhost:8080/api/demo/fixed-window?client=alice'
curl -i 'http://localhost:8080/api/demo/sliding-log?client=alice'
curl -i 'http://localhost:8080/api/demo/sliding-counter?client=alice'
curl -i 'http://localhost:8080/api/demo/token-bucket?client=alice'
curl -i 'http://localhost:8080/api/demo/leaky-bucket?client=alice'
```

Burst seven requests with a fresh client (Bash/zsh):

```sh
client="study-$(date +%s)"
for i in 1 2 3 4 5 6 7; do
  curl -s -w '\nHTTP %{http_code}\n' \
    "http://localhost:8080/api/demo/token-bucket?client=$client"
done
```

Normally the first five return **200** and the next two **429** if sent within
one second. Wait one second for one token to refill, or change `client` for fresh
state. Each technique has independent state. Example rejected body:

```json
{"technique":"token-bucket","client":"alice","allowed":false,"message":"Rate limit exceeded"}
```

Unknown techniques or invalid client IDs return 400. Redis failures return 503
(fail closed). Only accepted requests consume quota. This endpoint represents
where application work would run after admission.

## Five techniques at a glance

| Endpoint suffix | Sample limit | Redis state / key logic | Interview tradeoff |
|---|---|---|---|
| `fixed-window` | 5 / aligned 10 seconds | Hash: window ID + count | Cheap; up to 10 requests across a boundary |
| `sliding-log` | 5 / any rolling 10 seconds | Sorted set: prune timestamps, count, add | Exact; one entry per accepted request in window |
| `sliding-counter` | ~5 / rolling 10 seconds | Hash: current + weighted previous count | Constant space; assumes requests spread uniformly |
| `token-bucket` | Capacity 5, refill 1/second | Hash: tokens + last update | Allows bursts, controls sustained admission rate |
| `leaky-bucket` | Capacity 5, drain 1/second | Hash: virtual water + last update | Meter rejects overflow; tracks virtual backlog |

Examples: fixed window allows five just before and five just after a boundary.
Sliding log prevents that burst within any rolling ten seconds. Sliding counter
at 40% through a window estimates `current + previous * 0.6`; it admits only if
that estimate plus one is at most five. A full token bucket spends five tokens
immediately and earns one back each second. Leaky bucket adds five units of water
immediately and drains one per second.

**Leaky bucket distinction:** this minimal HTTP demo implements the *meter/policer*
variant. It admits immediately or rejects and is mathematically equivalent to the
token bucket with these settings. A *queue/shaper* variant would buffer jobs and
release them at a steady rate; it requires a worker and does not immediately
process the entire burst. Do not claim this demo smooths actual execution.

## Read the code

```text
src/main/java/com/example/ratelimiter/
  RateLimiterApplication.java       # entry point
  web/DemoController.java            # endpoint, 200/429/400/503 responses
  service/RateLimiterService.java    # sample limits, script loading, Redis call
src/main/resources/
  application.properties            # connection and port settings
  lua/                              # one commented script per technique
```

Start with the controller, then the service, then each Lua file. Change sample
limits in `RateLimiterService` (keep values positive). All scripts use Redis time,
one key per client/technique, and expiring state. Lua makes each read/check/write
sequence atomic even when multiple app instances share Redis. Scripts are cached
by Spring Data Redis. Refilling/draining is calculated on demand, without timers.

Study scope: client is a caller-controlled demo parameter, not an authenticated
identity. Redis restart/eviction can reset quota. Fixed and sliding windows follow
Redis wall-clock time; production designs also consider clock changes, Redis
availability, trusted identity, and operational capacity. No automated test suite
is included; use the curl burst as a quick smoke check.

References: [Spring Boot Java requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
and [Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html).
