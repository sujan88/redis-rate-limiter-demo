-- Atomically reserve up to a chunk, returning the granted count (not a boolean).
-- ARGV: capacity, refill tokens/sec, requested chunk. One key per customer+service.
local capacity, rate, chunk = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens')) or capacity
local last = tonumber(redis.call('HGET', KEYS[1], 'last')) or now
now = math.max(now, last)
tokens = math.min(capacity, tokens + (now - last) * rate / 1000)
local granted = math.min(chunk, math.floor(tokens))
redis.call('HSET', KEYS[1], 'tokens', tokens - granted, 'last', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000))
return granted
