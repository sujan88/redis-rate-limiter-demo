# Redis rate limiter

Java 17+, Maven 3.6.3+, Spring Boot 3.5.5, Spring Web and Spring Data Redis.
Lua scripts perform atomic checks and updates in Redis. The token-bucket endpoint uses Caffeine token leases. No database,
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

Unknown techniques or invalid client IDs return 400. Redis failures return 503 by default (fail closed); optional token-bucket fallback is described below. Token leases reserve quota in advance; unused expired reservations are discarded. This endpoint represents
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
  service/RateLimiterService.java    # technique dispatch and request metrics
  service/TokenLeaseLimiter.java     # Caffeine leases and separate fallback
src/main/resources/
  application.properties            # connection and port settings
  lua/                              # one commented script per technique
```

Start with the controller, then the services, then each Lua file. Change token
lease settings in `application.properties`; other sample limits are in
`RateLimiterService` (keep values positive). All scripts use Redis time,
one key per client/service/technique, and expiring state. Lua makes each read/check/write
sequence atomic even when multiple app instances share Redis. Scripts are cached
by Spring Data Redis. Refilling/draining is calculated on demand, without timers.

Study scope: client is a caller-controlled demo parameter, not an authenticated
identity. Redis restart/eviction can reset quota. Fixed and sliding windows follow
Redis wall-clock time; production designs also consider clock changes, Redis
availability, trusted identity, and operational capacity. Run `mvn test` for lease concurrency, expiry, partial-grant, failure, and recovery tests.

References: [Spring Boot Java requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
and [Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html).


## Caffeine token leasing

`client` represents the customer; `service` defaults to `default`. Both participate
in the cache and Redis key. For example:

```sh
curl -i 'http://localhost:8080/api/demo/token-bucket?client=alice&service=search'
```

`TokenLeaseLimiter.java` checks a Caffeine lease under a stable striped lock.
A remaining token is decremented locally with no Redis call. On an empty or
expired lease, `lua/token-lease.lua` lazily refills using Redis time, deducts
`min(requested chunk, floor(available tokens))` atomically, and returns that count.
The triggering request spends one; the rest are cached. A zero grant returns 429.
Concurrent requests for a key cannot reserve duplicate chunks or overspend locally.
Locks are per stripe, so unrelated keys can occasionally share a lock.

Defaults: capacity 5, refill 1/sec, chunk 2, Caffeine expiry 2 seconds after write,
maximum 10,000 leases per instance. Reads do not extend expiry. For chunk 50:

```sh
java -jar target/redis-rate-limiter-demo-1.0.0.jar \
  --rate-limit.capacity=100 --rate-limit.lease-size=50
```

At high traffic, a chunk of 50 amortizes one Redis script execution over up to 50
accepted requests. Idle expiry, eviction, and process death waste reserved tokens;
there is no refund. Denied traffic still contacts Redis per request. Tokens are
reserved ahead of consumption, so delayed consumption can bunch actual requests:
this is an approximate admission limiter, not strict global request timing.
A region or process cannot reissue someone else's lease. Redis data loss can still
reset quota. Use the same capacity/refill policy on all instances. The new Redis
key prefix is `demo:rl:token-lease:`; upgrading resets token-bucket demo state.
The old `token-bucket.lua` remains as the single-token study reference.

## Optional local fallback

The original fail-closed behavior remains the default: a Redis failure on lease
acquisition produces 503. Already reserved, unexpired tokens can still be used.
Other techniques always fail closed. Enable token-bucket local fallback explicitly:

```sh
java -jar target/redis-rate-limiter-demo-1.0.0.jar --rate-limit.local-fallback=true
```

Fallback has a SEPARATE Caffeine cache with a per-instance, per-customer/service
bucket: capacity 5, refill 1/sec, initially empty. First failure usually returns
429; after a second a token is available. Starting empty prevents eviction from
giving a free burst. Entries expire after one minute idle and the cache holds up
to 10,000 buckets. This is best-effort availability and can exceed the global Redis
quota across instances. It is not a Redis reservation. A successful Redis call
clears that key's fallback bucket; Redis quota denial never activates fallback.
Each lease miss retries Redis, so an outage incurs connection/command timeout
latency. A production circuit breaker is deliberately outside this small demo.

## Metrics: exact inspection commands

Actuator and the Prometheus registry expose `/actuator/metrics` and
`/actuator/prometheus`. Counters are cumulative since process start:

| Meter | Type | Meaning |
|---|---|---|
| `rate_limit.allowed` | Counter | Accepted requests, once per request |
| `rate_limit.denied` | Counter | Rejected requests, including fail-closed 503 |
| `rate_limit.redis_error` | Counter | Failed Redis attempts, not local lease hits |
| `rate_limit.fallback_active` | Gauge | 1 when the latest token-bucket Redis attempt failed and local fallback is enabled; 0 initially or after a successful attempt |

The gauge describes this instance's last observed mode, not a live Redis health
check or number of active keys. It stays unchanged without Redis attempts; a
successful attempt for any key clears it. In fail-closed mode it stays 0. A gauge
fits a reversible state; a counter would only accumulate activations. Counters
have `technique` and (for allowed/denied) `source=redis|lease|fallback|fail_closed`.
No customer/service tags are used, to avoid unbounded metric cardinality.

Generate allowed and denied requests with a fresh customer (defaults, Redis up):

```sh
client="metrics-$(date +%s)"
for i in $(seq 1 12); do
  curl -s -w '\nHTTP %{http_code}\n' \
    "http://localhost:8080/api/demo/token-bucket?client=$client&service=search"
done
```

Expect five initial 200s and then 429s if the burst finishes before refill.
The first request acquires a lease (`source=redis`), the second spends locally
(`source=lease`); repeat until quota runs out. Inspect all four names:

```sh
curl -s http://localhost:8080/actuator/metrics/rate_limit.allowed
curl -s http://localhost:8080/actuator/metrics/rate_limit.denied
curl -s http://localhost:8080/actuator/metrics/rate_limit.redis_error
curl -s http://localhost:8080/actuator/metrics/rate_limit.fallback_active
curl -s 'http://localhost:8080/actuator/metrics/rate_limit.allowed?tag=technique:token-bucket&tag=source:lease'
curl -s 'http://localhost:8080/actuator/metrics/rate_limit.allowed?tag=source:fallback'
curl -s http://localhost:8080/actuator/prometheus | grep '^rate_limit_'
```

Actuator reports `COUNT` for counters, `VALUE` for the gauge. Without tag filters,
counters aggregate matching series. Prometheus exports `rate_limit_allowed_total`,
`rate_limit_denied_total`, `rate_limit_redis_error_total`, and
`rate_limit_fallback_active` with labels.

Generate an outage with the demo Redis container (do not stop a shared Redis).
Start the app with `--rate-limit.local-fallback=true` first:

```sh
docker stop rate-limit-redis
client="outage-$(date +%s)"
curl -i "http://localhost:8080/api/demo/token-bucket?client=$client&service=search"
sleep 2
curl -i "http://localhost:8080/api/demo/token-bucket?client=$client&service=search"
curl -s http://localhost:8080/actuator/metrics/rate_limit.redis_error
curl -s http://localhost:8080/actuator/metrics/rate_limit.fallback_active
curl -s 'http://localhost:8080/actuator/metrics/rate_limit.allowed?tag=source:fallback'
curl -s 'http://localhost:8080/actuator/metrics/rate_limit.denied?tag=source:fallback'
# Earlier --rm container was removed on stop; recreate it:
docker run -d --rm --name rate-limit-redis -p 127.0.0.1:6379:6379 redis:7-alpine
curl -i "http://localhost:8080/api/demo/token-bucket?client=$client&service=search"
curl -s http://localhost:8080/actuator/metrics/rate_limit.fallback_active
```

The first outage request returns 429, the second should return 200 from fallback;
Redis errors increase and the gauge is 1. After reconnecting, the next successful
Redis attempt sets the gauge to 0. With the default fallback setting the outage
requests instead return 503, increment denied/source=fail_closed, and keep the
gauge at 0. Metrics are local to each application instance and reset on restart.
Exposed Actuator endpoints here are intended for this local demo.

References: [Spring Boot metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html),
[Micrometer Prometheus](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html),
[Caffeine expiry](https://github.com/ben-manes/caffeine/wiki/Eviction).


## Verified in this workspace

Java 17 `mvn package`: **5 tests passed**, executable JAR built.
A temporary standalone Redis and app on isolated ports verified:

- Burst: HTTP 200 × 5, then 429 × 2; two requests consumed local tokens.
- Lua partial grants: 2, 2, 1, 0; lazy refill granted tokens after waiting.
- Customer/service isolation.
- Outage with optional fallback: 429 initially, then 200 after local refill;
  Redis-error count reached 2 and fallback gauge became 1.
- Recovery: HTTP 200 and fallback gauge returned to 0.
- All four Prometheus metric names and Actuator readings.

Temporary processes were stopped after verification. On this machine Maven
selects a newer JDK by default; the verified build command was:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home mvn package
```

The Mockito test configuration uses its subclass mock maker, so these tests do
not require attaching a Java agent to the running JVM.
