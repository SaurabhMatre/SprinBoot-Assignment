package com.social.api.config;

public final class RedisKeys {

    private RedisKeys() {}

    /** Virality score for a post: post:{id}:virality_score */
    public static String viralityScore(Long postId) {
        return "post:" + postId + ":virality_score";
    }

    /** Total bot comment count on a post: post:{id}:bot_count */
    public static String botCount(Long postId) {
        return "post:" + postId + ":bot_count";
    }

    /** Cooldown key between bot and human: cooldown:bot_{botId}:human_{humanId} */
    public static String cooldown(Long botId, Long humanId) {
        return "cooldown:bot_" + botId + ":human_" + humanId;
    }

    /** Notification cooldown key per user: notif_cooldown:user_{userId} */
    public static String notifCooldown(Long userId) {
        return "notif_cooldown:user_" + userId;
    }

    /** Pending notifications list per user: user:{id}:pending_notifs */
    public static String pendingNotifs(Long userId) {
        return "user:" + userId + ":pending_notifs";
    }

    /** Pattern for scanning all pending notification keys */
    public static final String PENDING_NOTIFS_PATTERN = "user:*:pending_notifs";

    // Virality weights
    public static final long BOT_REPLY_POINTS     = 1L;
    public static final long HUMAN_LIKE_POINTS    = 20L;
    public static final long HUMAN_COMMENT_POINTS = 50L;

    // Guardrail limits
    public static final long HORIZONTAL_CAP = 100L;
    public static final long VERTICAL_CAP   = 20L;

    // TTLs (seconds)
    public static final long COOLDOWN_TTL_SECONDS       = 600L;   // 10 minutes
    public static final long NOTIF_COOLDOWN_TTL_SECONDS = 900L;   // 15 minutes
}
