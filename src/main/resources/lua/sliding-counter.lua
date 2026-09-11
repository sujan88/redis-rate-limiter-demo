-- ARGV: limit/capacity, window milliseconds, rate per second, unique request ID.
-- Redis time avoids disagreement between application instances. Return 1/0.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local key, limit = KEYS[1], tonumber(ARGV[1])

local window = tonumber(ARGV[2])
local bucket = math.floor(now / window)
local saved = tonumber(redis.call('HGET', key, 'bucket'))
local current = tonumber(redis.call('HGET', key, 'current')) or 0
local previous = tonumber(redis.call('HGET', key, 'previous')) or 0
if saved ~= bucket then
    if saved == bucket - 1 then previous = current else previous = 0 end
    current = 0
end
-- Approximate rolling usage by weighting the previous window's count.
local weight = 1 - (now % window) / window
if current + previous * weight + 1 > limit then return 0 end
redis.call('HSET', key, 'bucket', bucket, 'current', current + 1, 'previous', previous)
redis.call('PEXPIRE', key, 2 * window)
return 1
