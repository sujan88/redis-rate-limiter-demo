-- ARGV: limit/capacity, window milliseconds, rate per second, unique request ID.
-- Redis time avoids disagreement between application instances. Return 1/0.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local key, limit = KEYS[1], tonumber(ARGV[1])

local window = tonumber(ARGV[2])
-- Keep accepted timestamps in (now-window, now]. UUIDs prevent same-ms collisions.
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
if redis.call('ZCARD', key) >= limit then return 0 end
redis.call('ZADD', key, now, ARGV[4])
redis.call('PEXPIRE', key, window)
return 1
