-- ARGV: limit/capacity, window milliseconds, rate per second, unique request ID.
-- Redis time avoids disagreement between application instances. Return 1/0.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local key, limit = KEYS[1], tonumber(ARGV[1])

local window = tonumber(ARGV[2])
local bucket = math.floor(now / window)
local saved = tonumber(redis.call('HGET', key, 'bucket'))
local count = tonumber(redis.call('HGET', key, 'count')) or 0
-- Reset at an epoch-aligned boundary, not ten seconds after each request.
if saved ~= bucket then count = 0 end
if count >= limit then return 0 end
redis.call('HSET', key, 'bucket', bucket, 'count', count + 1)
redis.call('PEXPIRE', key, window - (now % window))
return 1
