-- Token bucket, evaluated atomically inside Redis so concurrent requests on any app instance share it.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (burst), ARGV[2] = refill rate in tokens per millisecond, ARGV[3] = cost
-- Returns {allowed (1/0), tokens left (floored), ms until enough tokens}
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

-- Redis's clock, not the app's: instances with skewed clocks still agree.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then
  tokens = capacity
  ts = now
end

tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)

local allowed = 0
local wait_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  wait_ms = math.ceil((cost - tokens) / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- Forget idle buckets once they'd be full again anyway.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
return {allowed, math.floor(tokens), wait_ms}
