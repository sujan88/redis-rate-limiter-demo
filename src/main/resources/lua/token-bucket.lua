-- ARGV: limit/capacity, window milliseconds, rate per second, unique request ID.
-- Redis time avoids disagreement between application instances. Return 1/0.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local key, limit = KEYS[1], tonumber(ARGV[1])

local rate = tonumber(ARGV[3])
local tokens = tonumber(redis.call('HGET', key, 'tokens')) or limit
local last = tonumber(redis.call('HGET', key, 'last')) or now
now = math.max(now, last)
-- Refill lazily; fractional tokens accumulate without a background timer.
tokens = math.min(limit, tokens + (now - last) * rate / 1000)
if tokens < 1 then return 0 end
redis.call('HSET', key, 'tokens', tokens - 1, 'last', now)
-- After this idle period, the bucket would be full anyway.
redis.call('PEXPIRE', key, math.ceil(limit / rate * 1000))
return 1
