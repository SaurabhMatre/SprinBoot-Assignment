package com.social.api.service;

import com.social.api.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * NotificationService implements the Redis Throttler described in Phase 3.
 *
 * Throttle logic:
 *   - If user has a notification cooldown key (sent within last 15 minutes) →
 *     push message to user's Redis List (pending_notifs).
 *   - Else → log immediate push notification + set 15-minute cooldown key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final StringRedisTemplate redisTemplate;

    /**
     * Handle a bot notification for a user.
     *
     * @param userId      The user who owns the post.
     * @param botName     The name of the bot that interacted.
     * @param postId      The post that was interacted with.
     */
    public void handleBotNotification(Long userId, String botName, Long postId) {
        String cooldownKey = RedisKeys.notifCooldown(userId);
        String pendingKey  = RedisKeys.pendingNotifs(userId);
        String message     = "Bot " + botName + " replied to your post #" + postId;

        Boolean hasCooldown = redisTemplate.hasKey(cooldownKey);

        if (Boolean.TRUE.equals(hasCooldown)) {
            // User already notified recently → queue for batch
            redisTemplate.opsForList().rightPush(pendingKey, message);
            log.info("Queued pending notification for user {}: {}", userId, message);
        } else {
            // First notification in this window → send immediately, set cooldown
            log.info("Push Notification Sent to User {}: {}", userId, message);
            redisTemplate.opsForValue().set(
                    cooldownKey,
                    "1",
                    Duration.ofSeconds(RedisKeys.NOTIF_COOLDOWN_TTL_SECONDS)
            );
        }
    }
}
