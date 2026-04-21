package com.social.api.service;

import com.social.api.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ViralityService {

    private final StringRedisTemplate redisTemplate;

    public enum InteractionType {
        BOT_REPLY, HUMAN_LIKE, HUMAN_COMMENT
    }

    /**
     * Atomically increment the virality score for a post.
     * Redis INCRBY is a single atomic command — no race condition possible.
     */
    public Long incrementViralityScore(Long postId, InteractionType type) {
        long points = switch (type) {
            case BOT_REPLY      -> RedisKeys.BOT_REPLY_POINTS;
            case HUMAN_LIKE     -> RedisKeys.HUMAN_LIKE_POINTS;
            case HUMAN_COMMENT  -> RedisKeys.HUMAN_COMMENT_POINTS;
        };

        String key = RedisKeys.viralityScore(postId);
        Long newScore = redisTemplate.opsForValue().increment(key, points);
        log.debug("Post {} virality score updated by {} points → {}", postId, points, newScore);
        return newScore;
    }

    public Long getViralityScore(Long postId) {
        String val = redisTemplate.opsForValue().get(RedisKeys.viralityScore(postId));
        return val == null ? 0L : Long.parseLong(val);
    }
}
