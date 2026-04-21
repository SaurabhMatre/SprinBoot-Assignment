package com.social.api.service;

import com.social.api.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * GuardrailService enforces all three atomic Redis caps for bot interactions.
 *
 * Thread-Safety Strategy:
 * ─────────────────────────────────────────────────────────────────────────
 * 1. HORIZONTAL CAP – Uses a Lua script executed atomically on the Redis server.
 *    The script does: GET → check → INCR in a single atomic block, which is
 *    impossible to interleave on a single-threaded Redis server. This prevents
 *    the TOCTOU race condition that would occur with separate GET + INCR calls.
 *
 * 2. COOLDOWN CAP – Uses Redis SET NX (set-if-not-exists) with an expiry TTL
 *    in a single atomic command. Only one caller wins; the rest see key-exists.
 *
 * 3. VERTICAL CAP – A pure integer comparison on the depth_level field supplied
 *    by the caller (already persisted in Postgres). No concurrent risk here since
 *    the value is read-only at check time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardrailService {

    private final StringRedisTemplate redisTemplate;

    /**
     * Lua script: atomically increments bot_count only if below the cap.
     * Returns the NEW count, or -1 if the cap was already hit.
     *
     * KEYS[1] = bot_count key
     * ARGV[1] = cap limit
     */
    private static final String INCREMENT_IF_BELOW_CAP_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if current == false then current = 0 else current = tonumber(current) end
            if current >= tonumber(ARGV[1]) then return -1 end
            return redis.call('INCR', KEYS[1])
            """;

    private final DefaultRedisScript<Long> incrementIfBelowCapScript = buildScript();

    private DefaultRedisScript<Long> buildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(INCREMENT_IF_BELOW_CAP_SCRIPT);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Check and enforce the Horizontal Cap (≤ 100 bot replies per post).
     *
     * @return true if the cap is NOT reached (interaction allowed), false if rejected.
     */
    public boolean checkAndIncrementBotCount(Long postId) {
        String key = RedisKeys.botCount(postId);
        Long result = redisTemplate.execute(
                incrementIfBelowCapScript,
                List.of(key),
                String.valueOf(RedisKeys.HORIZONTAL_CAP)
        );
        if (result == null || result == -1L) {
            log.warn("Horizontal cap hit for post {}. Bot comment rejected.", postId);
            return false;
        }
        log.debug("Bot count for post {} incremented to {}", postId, result);
        return true;
    }

    /**
     * Decrement bot count (called on rollback if DB write fails after Redis increment).
     */
    public void decrementBotCount(Long postId) {
        redisTemplate.opsForValue().decrement(RedisKeys.botCount(postId));
    }

    /**
     * Check the Vertical Cap (depth ≤ 20).
     *
     * @return true if allowed, false if rejected.
     */
    public boolean checkVerticalCap(int depthLevel) {
        if (depthLevel > RedisKeys.VERTICAL_CAP) {
            log.warn("Vertical cap hit at depth {}. Bot comment rejected.", depthLevel);
            return false;
        }
        return true;
    }

    /**
     * Check and enforce the Cooldown Cap (bot–human cooldown of 10 minutes).
     * Uses Redis SET NX (atomic set-if-not-exists) with TTL — single command, no race.
     *
     * @return true if interaction is allowed (no active cooldown), false if blocked.
     */
    public boolean checkAndSetCooldown(Long botId, Long humanId) {
        String key = RedisKeys.cooldown(botId, humanId);
        // setIfAbsent is a single atomic SETNX EX command
        Boolean set = redisTemplate.opsForValue().setIfAbsent(
                key,
                "1",
                Duration.ofSeconds(RedisKeys.COOLDOWN_TTL_SECONDS)
        );
        if (!Boolean.TRUE.equals(set)) {
            log.warn("Cooldown active: bot {} cannot interact with human {} for {} more seconds.",
                    botId, humanId, redisTemplate.getExpire(key));
            return false;
        }
        return true;
    }
}
