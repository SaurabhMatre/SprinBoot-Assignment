package com.social.api.scheduler;

import com.social.api.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * NotificationSweeper — Phase 3 CRON Sweeper.
 *
 * Runs every 5 minutes (300_000 ms). In production this would be 15 min.
 *
 * Algorithm:
 *  1. Scan Redis for all keys matching "user:*:pending_notifs".
 *  2. For each key, pop ALL pending messages atomically using LRANGE + DEL.
 *  3. Log a summarized notification: "Bot X and [N] others interacted with your posts."
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSweeper {

    private final StringRedisTemplate redisTemplate;

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void sweepPendingNotifications() {
        log.info("[SWEEPER] Running notification sweep...");

        Set<String> pendingKeys = redisTemplate.keys(RedisKeys.PENDING_NOTIFS_PATTERN);

        if (pendingKeys == null || pendingKeys.isEmpty()) {
            log.info("[SWEEPER] No pending notifications found.");
            return;
        }

        for (String key : pendingKeys) {
            processUserNotifications(key);
        }

        log.info("[SWEEPER] Sweep complete. Processed {} user queues.", pendingKeys.size());
    }

    private void processUserNotifications(String key) {
        // Extract userId from key pattern "user:{id}:pending_notifs"
        String userId = extractUserId(key);

        // Atomically get all messages and delete the list
        // LRANGE 0 -1 returns all elements; then DEL clears it.
        List<String> messages = redisTemplate.opsForList().range(key, 0, -1);

        if (messages == null || messages.isEmpty()) {
            return;
        }

        // Delete the list atomically before processing to avoid double-delivery
        redisTemplate.delete(key);

        int count = messages.size();
        String firstMessage = messages.get(0);

        // Extract bot name from first message (format: "Bot {name} replied to your post #{id}")
        String botName = extractBotName(firstMessage);

        if (count == 1) {
            log.info("[SWEEPER] Summarized Push Notification for user {}: {}", userId, firstMessage);
        } else {
            int others = count - 1;
            log.info("[SWEEPER] Summarized Push Notification for user {}: Bot {} and [{}] others interacted with your posts.",
                    userId, botName, others);
        }
    }

    private String extractUserId(String key) {
        // key = "user:{id}:pending_notifs"
        String[] parts = key.split(":");
        return parts.length >= 2 ? parts[1] : "unknown";
    }

    private String extractBotName(String message) {
        // message format: "Bot {name} replied to your post #{id}"
        if (message.startsWith("Bot ")) {
            int end = message.indexOf(" replied");
            if (end > 4) {
                return message.substring(4, end);
            }
        }
        return "Unknown";
    }
}
