package com.social.api.service;

import com.social.api.dto.PostDtos.*;
import com.social.api.entity.Comment;
import com.social.api.entity.Post;
import com.social.api.entity.PostLike;
import com.social.api.repository.BotRepository;
import com.social.api.repository.CommentRepository;
import com.social.api.repository.PostLikeRepository;
import com.social.api.repository.PostRepository;
import com.social.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

/**
 * PostService orchestrates:
 *  1. Guardrail checks (Redis) — must pass before any DB write.
 *  2. DB persistence (Postgres) — source of truth for content.
 *  3. Virality score updates (Redis) — post-write, best-effort.
 *  4. Notification dispatch (Redis) — for bot interactions.
 *
 * Data Integrity guarantee:
 *  For bot comments, Redis bot_count is incremented FIRST inside the
 *  guardrail check. If the subsequent DB transaction fails, we decrement
 *  the counter back (compensating transaction pattern). This ensures
 *  Redis count never exceeds actual DB rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository        postRepository;
    private final CommentRepository     commentRepository;
    private final PostLikeRepository    postLikeRepository;
    private final UserRepository        userRepository;
    private final BotRepository         botRepository;
    private final GuardrailService      guardrailService;
    private final ViralityService       viralityService;
    private final NotificationService   notificationService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE POST
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public PostResponse createPost(CreatePostRequest req) {
        validateAuthor(req.getAuthorId(), req.getAuthorType().name());

        Post post = Post.builder()
                .authorId(req.getAuthorId())
                .authorType(req.getAuthorType())
                .content(req.getContent())
                .build();

        post = postRepository.save(post);
        log.info("Post created: id={}, authorType={}, authorId={}", post.getId(), post.getAuthorType(), post.getAuthorId());
        return toPostResponse(post);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADD COMMENT
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public CommentResponse addComment(Long postId, CreateCommentRequest req) {
        // Verify post exists
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        boolean isBot = req.getAuthorType() == Comment.AuthorType.BOT;

        if (isBot) {
            runBotGuardrails(postId, req);
        }

        Comment comment;
        try {
            comment = Comment.builder()
                    .postId(postId)
                    .authorId(req.getAuthorId())
                    .authorType(req.getAuthorType())
                    .content(req.getContent())
                    .depthLevel(req.getDepthLevel())
                    .build();

            comment = commentRepository.save(comment);
            log.info("Comment saved: id={}, postId={}, authorType={}", comment.getId(), postId, comment.getAuthorType());

        } catch (Exception e) {
            // If DB write fails after Redis bot_count was incremented, roll back the counter
            if (isBot) {
                guardrailService.decrementBotCount(postId);
                log.error("DB write failed for bot comment; Redis bot_count decremented for post {}", postId);
            }
            throw e;
        }

        // Update virality score (non-critical, don't roll back comment on failure)
        if (isBot) {
            viralityService.incrementViralityScore(postId, ViralityService.InteractionType.BOT_REPLY);

            // Notify post owner (if post was authored by a human)
            if (post.getAuthorType() == Post.AuthorType.USER && req.getPostOwnerId() != null) {
                String botName = botRepository.findById(req.getAuthorId())
                        .map(b -> b.getName())
                        .orElse("Unknown Bot");
                notificationService.handleBotNotification(post.getAuthorId(), botName, postId);
            }
        } else {
            viralityService.incrementViralityScore(postId, ViralityService.InteractionType.HUMAN_COMMENT);
        }

        return toCommentResponse(comment);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIKE POST
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public PostResponse likePost(Long postId, LikeRequest req) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        userRepository.findById(req.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + req.getUserId()));

        if (postLikeRepository.existsByPostIdAndUserId(postId, req.getUserId())) {
            throw new IllegalStateException("User " + req.getUserId() + " already liked post " + postId);
        }

        postLikeRepository.save(PostLike.builder()
                .postId(postId)
                .userId(req.getUserId())
                .build());

        Long newScore = viralityService.incrementViralityScore(postId, ViralityService.InteractionType.HUMAN_LIKE);
        log.info("Post {} liked by user {}. New virality score: {}", postId, req.getUserId(), newScore);

        PostResponse response = toPostResponse(post);
        response.setViralityScore(newScore);
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET VIRALITY SCORE
    // ─────────────────────────────────────────────────────────────────────────

    public ViralityResponse getViralityScore(Long postId) {
        postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        ViralityResponse response = new ViralityResponse();
        response.setPostId(postId);
        response.setViralityScore(viralityService.getViralityScore(postId));
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run all three Redis guardrails for a bot comment.
     * Order matters: vertical cap check is cheap (no Redis), do first.
     * Horizontal cap check does the atomic INCR; do after vertical.
     * Cooldown check is last; after horizontal to avoid needless Redis calls.
     */
    private void runBotGuardrails(Long postId, CreateCommentRequest req) {
        // 1. Vertical cap (no Redis I/O)
        if (!guardrailService.checkVerticalCap(req.getDepthLevel())) {
            throw new GuardrailException(
                "Rejected: comment depth " + req.getDepthLevel() + " exceeds maximum of 20.");
        }

        // 2. Horizontal cap (Lua atomic INCR) — INCR happens here
        if (!guardrailService.checkAndIncrementBotCount(postId)) {
            throw new GuardrailException(
                "Rejected: post " + postId + " has reached the maximum of 100 bot replies.");
        }

        // 3. Cooldown cap (SETNX) — only if postOwnerId provided
        if (req.getPostOwnerId() != null) {
            if (!guardrailService.checkAndSetCooldown(req.getAuthorId(), req.getPostOwnerId())) {
                // Roll back the horizontal cap increment we just did
                guardrailService.decrementBotCount(postId);
                throw new GuardrailException(
                    "Rejected: bot " + req.getAuthorId() +
                    " is in cooldown with human " + req.getPostOwnerId() + ".");
            }
        }
    }

    private void validateAuthor(Long authorId, String authorType) {
        if ("USER".equals(authorType)) {
            userRepository.findById(authorId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + authorId));
        } else if ("BOT".equals(authorType)) {
            botRepository.findById(authorId)
                    .orElseThrow(() -> new IllegalArgumentException("Bot not found: " + authorId));
        }
    }

    private PostResponse toPostResponse(Post post) {
        PostResponse r = new PostResponse();
        r.setId(post.getId());
        r.setAuthorId(post.getAuthorId());
        r.setAuthorType(post.getAuthorType());
        r.setContent(post.getContent());
        r.setCreatedAt(post.getCreatedAt() != null ? post.getCreatedAt().format(FMT) : null);
        r.setViralityScore(viralityService.getViralityScore(post.getId()));
        return r;
    }

    private CommentResponse toCommentResponse(Comment c) {
        CommentResponse r = new CommentResponse();
        r.setId(c.getId());
        r.setPostId(c.getPostId());
        r.setAuthorId(c.getAuthorId());
        r.setAuthorType(c.getAuthorType());
        r.setContent(c.getContent());
        r.setDepthLevel(c.getDepthLevel());
        r.setCreatedAt(c.getCreatedAt() != null ? c.getCreatedAt().format(FMT) : null);
        return r;
    }
}
