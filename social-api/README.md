# Social API — Core API & Guardrails Microservice

Spring Boot microservice implementing a high-performance social platform backend with Redis-powered guardrails and a smart notification engine.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 17, Spring Boot 3.2 |
| Database | PostgreSQL 16 (source of truth) |
| Cache / Locks | Redis 7 (gatekeeper) |
| ORM | JPA / Hibernate |
| Build | Maven |

---

## Quick Start

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- **PostgreSQL** on `localhost:5432` (db: `socialdb`, user/pass: `postgres`)
- **Redis** on `localhost:6379`

### 2. Run the Application

```bash
./mvnw spring-boot:run
```

Hibernate auto-creates all tables on first boot (`spring.jpa.hibernate.ddl-auto=update`).

### 3. Import Postman Collection

Import `postman/Social_API.postman_collection.json` into Postman and run the collection in order.

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/posts` | Create a post (USER or BOT) |
| `POST` | `/api/posts/{id}/comments` | Add a comment (runs guardrails if BOT) |
| `POST` | `/api/posts/{id}/like` | Like a post (human only) |
| `GET`  | `/api/posts/{id}/virality` | Get current virality score |

### Example: Create Post
```json
POST /api/posts
{
  "authorId": 1,
  "authorType": "USER",
  "content": "Hello world!"
}
```

### Example: Bot Comment
```json
POST /api/posts/1/comments
{
  "authorId": 1,
  "authorType": "BOT",
  "content": "Interesting take!",
  "depthLevel": 0,
  "postOwnerId": 1
}
```

---

## Architecture

```
Request
  │
  ▼
PostController
  │
  ├─► GuardrailService (Redis atomic checks — gatekeeper)
  │     ├── Vertical Cap   → integer check, no Redis I/O
  │     ├── Horizontal Cap → Lua script atomic INCR
  │     └── Cooldown Cap   → SETNX EX (single atomic command)
  │
  ├─► PostService / CommentRepository (Postgres — source of truth)
  │
  ├─► ViralityService (Redis INCRBY)
  │
  └─► NotificationService (Redis SETNX + RPUSH)
        │
        └─► NotificationSweeper (@Scheduled CRON — every 5 min)
```

---

## Thread-Safety for Atomic Locks (Phase 2)

### The Core Problem

With 200 concurrent bot requests hitting the same post, naive code like this **will** allow more than 100 comments:

```java
// WRONG — TOCTOU race condition
long count = redis.get("post:1:bot_count");  // Thread A reads 99
                                              // Thread B reads 99 (simultaneously)
if (count < 100) {
    redis.incr("post:1:bot_count");           // Thread A increments → 100
                                              // Thread B increments → 101 ← BUG
}
```

### Solution: Lua Script (Horizontal Cap)

Redis executes Lua scripts **atomically** — the entire script runs as a single unit with no interleaving possible, because Redis is single-threaded at the command execution level.

```lua
-- Runs atomically on the Redis server. No two callers can interleave.
local current = redis.call('GET', KEYS[1])
if current == false then current = 0 else current = tonumber(current) end
if current >= tonumber(ARGV[1]) then return -1 end   -- cap hit, reject
return redis.call('INCR', KEYS[1])                    -- safe to increment
```

This script is loaded as a `DefaultRedisScript<Long>` and executed via `redisTemplate.execute()`. The result of `-1` signals a rejected request.

**Guarantee**: With 200 concurrent threads all executing this script simultaneously, exactly 100 will receive a positive return value and proceed. The remaining 100 will receive `-1` and be rejected with HTTP 429.

### Solution: SET NX (Cooldown Cap)

The `SETNX` (set-if-not-exists) command with an expiry is a **single atomic Redis command**:

```java
redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(600));
```

This maps to the Redis command `SET key 1 NX EX 600`. Only the first caller wins; all subsequent callers within 10 minutes see the key already exists and are blocked. No race condition is possible.

### Vertical Cap

A simple integer comparison (`depthLevel > 20`) on the request payload. Since `depthLevel` is a property of the comment being created (not a shared mutable counter), there is no concurrency concern.

---

## Data Integrity

**Redis acts as the gatekeeper; Postgres is the source of truth.**

For bot comments, the order of operations is:
1. Redis guardrail check + `INCR` bot_count (atomic Lua)
2. Postgres DB write (inside `@Transactional`)
3. If DB write fails → `decrementBotCount()` compensates the Redis counter

This ensures the Redis counter **never exceeds** the actual number of rows in Postgres, even under failure scenarios.

---

## Virality Score Weights

| Interaction | Points |
|-------------|--------|
| Bot Reply | +1 |
| Human Like | +20 |
| Human Comment | +50 |

Scores are stored in Redis key `post:{id}:virality_score` using atomic `INCRBY`.

---

## Redis Key Schema

| Key Pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `post:{id}:virality_score` | String (int) | None | Cumulative virality score |
| `post:{id}:bot_count` | String (int) | None | Total bot replies on a post |
| `cooldown:bot_{id}:human_{id}` | String | 10 min | Bot–human interaction cooldown |
| `notif_cooldown:user_{id}` | String | 15 min | User notification cooldown |
| `user:{id}:pending_notifs` | List | None | Queued notification messages |

---

## Notification Engine (Phase 3)

When a bot interacts with a user's post:

```
Bot interaction
      │
      ▼
notif_cooldown:user_{id} exists?
      │
      ├── YES → RPUSH to user:{id}:pending_notifs
      │          (batch for later)
      │
      └── NO  → Log "Push Notification Sent to User {id}"
                Set notif_cooldown key (TTL 15 min)
```

Every 5 minutes, `NotificationSweeper` runs:
1. Scans `user:*:pending_notifs` keys
2. For each key: pops all messages, deletes the list
3. Logs: `"Bot X and [N] others interacted with your posts."`

---

## Statelessness

The application is **completely stateless** — no `HashMap`, `static` variables, or in-memory counters anywhere. All shared state lives exclusively in Redis:
- Counters → Redis strings with `INCR`
- Cooldowns → Redis keys with TTL
- Pending notifications → Redis lists

Any number of application instances can run in parallel and will share the same Redis state correctly.

---

## Project Structure

```
src/main/java/com/social/api/
├── SocialApiApplication.java
├── config/
│   ├── RedisConfig.java          # RedisTemplate beans
│   └── RedisKeys.java            # All key patterns & constants
├── entity/
│   ├── User.java
│   ├── Bot.java
│   ├── Post.java
│   ├── Comment.java
│   └── PostLike.java
├── repository/                    # JpaRepository per entity
├── dto/
│   └── PostDtos.java             # Request/Response DTOs
├── service/
│   ├── GuardrailService.java     # Atomic Redis locks (Lua + SETNX)
│   ├── ViralityService.java      # INCRBY score updates
│   ├── NotificationService.java  # Throttler logic
│   ├── PostService.java          # Orchestration layer
│   └── GuardrailException.java   # → HTTP 429
├── scheduler/
│   └── NotificationSweeper.java  # @Scheduled CRON sweeper
└── controller/
    └── PostController.java       # REST endpoints
```
