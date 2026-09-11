-- ARGV: limit/capacity, window milliseconds, rate per second, unique request ID.
-- Redis time avoids disagreement between application instances. Return 1/0.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local key, limit = KEYS[1], tonumber(ARGV[1])

local rate = tonumber(ARGV[3])
local water = tonumber(redis.call('HGET', key, 'water')) or 0
local last = tonumber(redis.call('HGET', key, 'last')) or now
now = math.max(now, last)
-- Meter variant: drain virtual backlog, reject if adding one would overflow.
-- This does NOT queue or delay HTTP requests; see README for the shaper variant.
water = math.max(0, water - (now - last) * rate / 1000)
if water + 1 > limit then return 0 end
redis.call('HSET', key, 'water', water + 1, 'last', now)
redis.call('PEXPIRE', key, math.ceil(limit / rate * 1000))
return 1
