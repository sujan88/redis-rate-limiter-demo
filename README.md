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


## Send metrics directly to New Relic

No Prometheus server is needed. The `newrelic` Spring profile enables the
Micrometer OTLP exporter, which pushes metrics every 30 seconds. Counters export
interval deltas so New Relic can sum requests over time. Ordinary local runs keep
OTLP disabled and do not require credentials.

An installed New Relic APM agent can continue handling APM. This exporter sends
Micrometer metrics directly; it does not automatically read the agent's YAML
license configuration. Provide the ingest license key through the environment.
If an existing integration already exports these same custom counters, use one
export path for them to avoid double counting.

From this project folder, with Redis running:

```sh
export NEW_RELIC_LICENSE_KEY='your-ingest-license-key'
mvn spring-boot:run -Dspring-boot.run.profiles=newrelic
```

Or use the built JAR:

```sh
java -jar target/redis-rate-limiter-demo-1.0.0.jar --spring.profiles.active=newrelic
```

Keep credentials out of Git. For an EU account, set this before starting:

```sh
export NEW_RELIC_OTLP_METRICS_URL=https://otlp.eu01.nr-data.net/v1/metrics
```

Generate traffic, then allow a minute or two for export and ingestion:

```sh
client="nr-$(date +%s)"
for i in {1..20}; do
  curl -s -o /dev/null -w '%{http_code}\n' \
    "http://localhost:8080/api/demo/token-bucket?client=$client&service=search"
done
```

In New Relic's NRQL query interface, count denied requests over four hours:

```sql
FROM Metric
SELECT sum(rate_limit.denied) AS 'Denied requests'
WHERE application = 'redis-rate-limiter-demo'
SINCE 4 hours ago
```

For a breakdown, add `FACET technique, source`. Denied includes 429 and fail-closed
503; add `AND source != 'fail_closed'` to count only quota rejections.

Inspect the other counters:

```sql
FROM Metric
SELECT sum(rate_limit.allowed), sum(rate_limit.redis_error)
WHERE application = 'redis-rate-limiter-demo'
SINCE 1 hour ago
```

The fallback gauge is a state, not a count. Show the latest observation per instance:

```sql
FROM Metric
SELECT latest(rate_limit.fallback_active)
WHERE application = 'redis-rate-limiter-demo'
FACET `service.instance.id`
SINCE 10 minutes ago
```

For the outage exercise above, also pass `--rate-limit.local-fallback=true` when
running the JAR. The application tag is included on all meters. OTLP resource
attributes identify the service/instance. History starts when collection starts;
queries cannot recover measurements from before export was enabled.

If no data appears, check app logs for export errors: authentication failures
usually mean the wrong ingest key/account region, and connection failures mean
the OTLP endpoint cannot be reached. Local Actuator readings still work.

References: [Micrometer OTLP](https://docs.micrometer.io/micrometer/reference/implementations/otlp.html)
and [New Relic OTLP ingest](https://docs.newrelic.com/docs/opentelemetry/best-practices/opentelemetry-otlp/).


## Local testing: settings in a properties file

Instead of terminal exports, edit `newrelic-local.properties` in the project root.
It is excluded from Git and is not packaged inside the JAR. A placeholder file is
provided locally; fresh checkouts can copy `newrelic-local.properties.example`.
Use plain `KEY=value` lines without quotes, `export`, or spaces around `=`.
Set your ingest license key and the absolute path to your installed `newrelic.jar`.
For an EU account, change the OTLP URL to `https://otlp.eu01.nr-data.net/v1/metrics`.

```sh
mvn package
./run-newrelic.sh --rate-limit.local-fallback=true
```

The launcher reads the file and supplies the settings to the Java agent for log
forwarding and to Spring for metrics. The Java agent itself does not read Spring
properties. No manual terminal exports are needed. The launcher validates that
the key has been filled in and the agent JAR exists before starting the app.

For metrics only, run the JAR from the project root with
`--spring.profiles.active=newrelic`; Spring imports the local properties file.
The Java agent and launcher are only needed here for APM/log forwarding.

## Reusable New Relic verification checklist

### 1. Obtain the correct key

Open New Relic's API keys page and select the account where you will query data.
Use the full value of an **INGEST - LICENSE** key. A **Key ID**, USER key, browser
key, or masked value containing `****` will not work for this setup.

Open the license row's `…` menu and look for an option to copy/view its full value.
If that option is unavailable, use **Create a key**, choose the intended account
and **Ingest - License**, and name it `local-rate-limiter`. Save the full value
when it is presented. UI options depend on permissions; if you cannot create or
view an ingest license key, ask your account administrator. Do not copy Key ID.

### 2. Save local settings

From the project root, create the local file only if it does not already exist:

```sh
[ -f newrelic-local.properties ] || cp newrelic-local.properties.example newrelic-local.properties
```

Edit it with your real values (no quotes or spaces around `=`):

```properties
NEW_RELIC_LICENSE_KEY=your-full-ingest-license-key-value
NEW_RELIC_APP_NAME=redis-rate-limiter-demo
NEW_RELIC_APPLICATION_LOGGING_ENABLED=true
NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED=true
NEW_RELIC_OTLP_METRICS_URL=https://otlp.nr-data.net/v1/metrics
NEW_RELIC_AGENT_JAR=/absolute/path/to/newrelic.jar
```

For an EU account, use `https://otlp.eu01.nr-data.net/v1/metrics`. Keep this file
out of Git; `.gitignore` already excludes it. Restart the application after
changing settings: the running process does not reload these credentials.

### 3. Install the Java agent if you also want logs

The Mac infrastructure agent and CLI do not include the Java APM agent.
Metrics use OTLP and do not require `newrelic.jar`; our combined launcher requires
it for APM and automatic application log forwarding.

From the project root, if the Java agent is not already installed:

```sh
mkdir -p newrelic
curl -fL https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic.jar \
  -o newrelic/newrelic.jar
```

Set `NEW_RELIC_AGENT_JAR` to the absolute path of that downloaded file. Ensure
`newrelic/` is in `.gitignore` so the binary and agent logs are not committed.

### 4. Start Redis and the application

Start the demo Redis container if you do not already have Redis running:

```sh
docker run -d --rm --name rate-limit-redis -p 127.0.0.1:6379:6379 redis:7-alpine
```

Build and start from the project root:

```sh
mvn package
./run-newrelic.sh --rate-limit.local-fallback=true
```

If Maven selects an incompatible Java installation on this Mac, build with:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home mvn package
```

Keep the app terminal open and wait for `Started RateLimiterApplication`.
For metrics only, you can instead start without the Java agent:

```sh
java -jar target/redis-rate-limiter-demo-1.0.0.jar \
  --spring.profiles.active=newrelic --rate-limit.local-fallback=true
```

### 5. Generate traffic and verify locally

In a second terminal:

```sh
client="newrelic-check-$(date +%s)"
for i in {1..20}; do
  curl -s -o /dev/null -w '%{http_code}\n' \
    "http://localhost:8080/api/demo/token-bucket?client=$client&service=search"
done
curl -s localhost:8080/actuator/metrics/rate_limit.allowed
curl -s localhost:8080/actuator/metrics/rate_limit.denied
curl -s localhost:8080/actuator/metrics/rate_limit.redis_error
curl -s localhost:8080/actuator/metrics/rate_limit.fallback_active
```

Expect initial 200s then 429s. These Actuator counters are local totals since
startup; New Relic stores exported interval counts for historical queries.

### 6. Query metrics in New Relic

Click **Query your data** (bottom-left in the UI shown during setup), select the
account matching the ingest key, paste a query, and click **Run**. Allow 1–2
minutes after generating traffic; metrics export every 30 seconds.

Allowed, denied, and Redis errors over the last hour:

```sql
FROM Metric
SELECT sum(rate_limit.allowed), sum(rate_limit.denied), sum(rate_limit.redis_error)
WHERE application = 'redis-rate-limiter-demo'
SINCE 1 hour ago
```

Denied token-bucket requests over the last four hours, grouped by source:

```sql
FROM Metric
SELECT sum(rate_limit.denied) AS 'Denied requests'
WHERE application = 'redis-rate-limiter-demo' AND technique = 'token-bucket'
FACET source
SINCE 4 hours ago
```

Remove `FACET source` for one total. Add `AND source != 'fail_closed'` to exclude
503s and count only 429 quota rejections. Normal local lease consumption has
`source = 'lease'`; fallback admissions have `source = 'fallback'`.

For one running demo instance, inspect the fallback state:

```sql
FROM Metric
SELECT latest(rate_limit.fallback_active)
WHERE application = 'redis-rate-limiter-demo'
SINCE 10 minutes ago
```

Use `latest`, not `sum`, for this gauge. It is 1 after observed local fallback,
and 0 after a successful token-bucket Redis attempt. It is not a live health
probe. For multiple instances, filter/facet by the instance attribute present
in your exported data instead of combining their latest states.

### 7. Exercise Redis errors and fallback

Stop only your demo Redis container, then use a fresh customer:

```sh
docker stop rate-limit-redis
client="nr-outage-$(date +%s)"
curl -i "http://localhost:8080/api/demo/token-bucket?client=$client"
sleep 2
curl -i "http://localhost:8080/api/demo/token-bucket?client=$client"
```

With fallback enabled, expect 429 initially and 200 after local refill. Keep the
app running through an export interval and query the counters/gauge above.
Restore Redis and make another request to observe recovery:

```sh
docker run -d --rm --name rate-limit-redis -p 127.0.0.1:6379:6379 redis:7-alpine
curl -i "http://localhost:8080/api/demo/token-bucket?client=$client"
```

After a successful Redis attempt, fallback_active returns to 0. Short-lived
state changes between exports may not appear in the sampled gauge history.

### 8. Query application logs (Java agent required)

No extra activation in New Relic's **Configure logs** wizard is needed when the
Java agent is already forwarding logs. First check whether any logs arrived:

```sql
FROM Log SELECT * SINCE 30 minutes ago LIMIT 100
```

Then filter to this app:

```sql
FROM Log
SELECT *
WHERE entity.name = 'redis-rate-limiter-demo'
SINCE 30 minutes ago
LIMIT 100
```

Expect application logs such as startup messages. The demo records rate-limit
decisions as metrics; it does not log each accepted or rejected request.

### Troubleshooting encountered during setup

| Symptom | Check / fix |
|---|---|
| curl cannot connect to port 8080 | App has not started or exited. Read its terminal output; wait for the startup success message. Use a plain URL, not Markdown `[url](url)` syntax. |
| Launcher asks for a key or agent path | Replace the placeholder in the local properties file and provide an existing agent JAR path. |
| OTLP HTTP 403 | Verify the full INGEST - LICENSE value, account region, and restart after saving. A Key ID cannot authenticate. |
| Agent reports `Invalid license key` | Correct the ingest key and restart; log forwarding cannot work until the agent authenticates. |
| Local metrics exist but New Relic is empty | Confirm the newrelic profile, inspect exporter errors, wait for export, and select the correct account/time range. |
| Metrics appear but no logs | Start with the Java agent via the launcher; confirm forwarding is enabled and inspect `newrelic/logs/newrelic_agent.log` when the agent is installed under `newrelic/`. |
| Old four-hour history is missing | Collection starts when export is enabled; earlier data cannot be reconstructed. |

Never include a real key in screenshots, shared logs, or Git commits.

## New Relic sample queries — copy and paste

Open **Query your data**, select your account, paste one query, then click **Run**.
These queries use the metrics exported by this project. Counters use `sum`;
the fallback gauge uses `latest` or `max`. Time ranges are relative to now.

### All request counters in the last hour

```sql
FROM Metric
SELECT sum(rate_limit.allowed) AS 'Allowed',
       sum(rate_limit.denied) AS 'Denied',
       sum(rate_limit.redis_error) AS 'Redis errors'
WHERE application = 'redis-rate-limiter-demo'
SINCE 1 hour ago
```

### Total denied requests in the last four hours

```sql
FROM Metric
SELECT sum(rate_limit.denied) AS 'Denied requests'
WHERE application = 'redis-rate-limiter-demo'
SINCE 4 hours ago
```

### Only HTTP 429 denials (exclude fail-closed HTTP 503)

```sql
FROM Metric
SELECT sum(rate_limit.denied) AS 'Quota denials'
WHERE application = 'redis-rate-limiter-demo'
  AND source != 'fail_closed'
SINCE 4 hours ago
```

### Allowed and denied by rate-limiting technique

```sql
FROM Metric
SELECT sum(rate_limit.allowed) AS 'Allowed',
       sum(rate_limit.denied) AS 'Denied'
WHERE application = 'redis-rate-limiter-demo'
FACET technique
SINCE 1 hour ago
```

### Token-bucket requests by source

```sql
FROM Metric
SELECT sum(rate_limit.allowed) AS 'Allowed',
       sum(rate_limit.denied) AS 'Denied'
WHERE application = 'redis-rate-limiter-demo'
  AND technique = 'token-bucket'
FACET source
SINCE 1 hour ago
```

`lease` means a local reserved token; `redis` means a request that acquired a
lease or received a zero grant; `fallback` means the independent local fallback;
`fail_closed` means a Redis failure rejected the request with 503.

### Requests served from local Caffeine leases

```sql
FROM Metric
SELECT sum(rate_limit.allowed) AS 'Local lease admissions'
WHERE application = 'redis-rate-limiter-demo'
  AND technique = 'token-bucket'
  AND source = 'lease'
SINCE 1 hour ago
```

This counts requests that consumed an already reserved token without calling
Redis. The request that obtains a fresh lease is counted under `source = 'redis'`.

### Denial percentage (includes fail-closed errors)

```sql
FROM Metric
SELECT 100.0 * sum(rate_limit.denied) /
       (sum(rate_limit.allowed) + sum(rate_limit.denied)) AS 'Denied percent'
WHERE application = 'redis-rate-limiter-demo'
SINCE 1 hour ago
```

When there is no traffic, the denominator is zero and no meaningful percentage
is available. Redis-error events are not added to the denominator: those requests
are already represented by an allowed or denied counter.

### Allowed and denied requests over time

```sql
FROM Metric
SELECT sum(rate_limit.allowed) AS 'Allowed',
       sum(rate_limit.denied) AS 'Denied'
WHERE application = 'redis-rate-limiter-demo'
SINCE 1 hour ago
TIMESERIES 5 minutes
```

Each point represents the count in that five-minute interval.

### Redis errors over time

```sql
FROM Metric
SELECT sum(rate_limit.redis_error) AS 'Redis errors'
WHERE application = 'redis-rate-limiter-demo'
FACET technique
SINCE 1 hour ago
TIMESERIES 5 minutes
```

### Requests admitted or denied by local fallback

```sql
FROM Metric
SELECT sum(rate_limit.allowed) AS 'Fallback allowed',
       sum(rate_limit.denied) AS 'Fallback denied'
WHERE application = 'redis-rate-limiter-demo'
  AND technique = 'token-bucket'
  AND source = 'fallback'
SINCE 1 hour ago
```

### Latest fallback state — single demo instance

```sql
FROM Metric
SELECT latest(rate_limit.fallback_active) AS 'Fallback active'
WHERE application = 'redis-rate-limiter-demo'
  AND technique = 'token-bucket'
SINCE 10 minutes ago
```

1 means the latest observed mode is fallback; 0 means it is not. No data does
not mean 0. With multiple instances, facet/filter by an instance attribute that
is actually present in your data; otherwise `latest` selects the latest sample
across instances rather than describing each instance.

### Was fallback observed during each five-minute interval?

```sql
FROM Metric
SELECT max(rate_limit.fallback_active) AS 'Fallback observed'
WHERE application = 'redis-rate-limiter-demo'
SINCE 1 hour ago
TIMESERIES 5 minutes
```

A value of 1 means at least one exported sample was active in that interval.
This does not measure outage duration or count activations. Brief transitions
between exports can be missed.

### Application logs (Java agent forwarding required)

```sql
FROM Log
SELECT *
WHERE entity.name = 'redis-rate-limiter-demo'
SINCE 30 minutes ago
LIMIT 100
```

### WARN and ERROR application logs

```sql
FROM Log
SELECT *
WHERE entity.name = 'redis-rate-limiter-demo'
  AND level IN ('WARN', 'ERROR')
SINCE 1 hour ago
LIMIT 100
```

This assumes the forwarded records expose severity as `level`; inspect a log
record if your forwarding integration uses a different attribute.

### Find Redis-related log messages

```sql
FROM Log
SELECT *
WHERE entity.name = 'redis-rate-limiter-demo'
  AND message LIKE '%Redis%'
SINCE 1 hour ago
LIMIT 100
```

Only messages actually logged and forwarded can appear. This project does not
log each rate-limit decision, so use the metric queries for request totals.

### Discover exported metric names when debugging empty results

```sql
FROM Metric
SELECT uniques(metricName)
WHERE application = 'redis-rate-limiter-demo'
  AND metricName LIKE 'rate_limit.%'
SINCE 1 hour ago
```

If this is empty, check the selected account, active `newrelic` profile, time
range, ingest key, and exporter errors. No Prometheus server is required.
