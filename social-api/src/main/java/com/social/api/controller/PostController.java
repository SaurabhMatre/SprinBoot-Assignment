package com.social.api.controller;

import com.social.api.dto.PostDtos.*;
import com.social.api.service.GuardrailException;
import com.social.api.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/posts
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<PostResponse> createPost(@Valid @RequestBody CreatePostRequest req) {
        PostResponse response = postService.createPost(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/posts/{postId}/comments
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{postId}/comments")
    public ResponseEntity<?> addComment(
            @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequest req) {
        try {
            CommentResponse response = postService.addComment(postId, req);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (GuardrailException e) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse(429, e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/posts/{postId}/like
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{postId}/like")
    public ResponseEntity<?> likePost(
            @PathVariable Long postId,
            @Valid @RequestBody LikeRequest req) {
        try {
            PostResponse response = postService.likePost(postId, req);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(409, e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/posts/{postId}/virality
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{postId}/virality")
    public ResponseEntity<ViralityResponse> getVirality(@PathVariable Long postId) {
        return ResponseEntity.ok(postService.getViralityScore(postId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Global exception handlers
    // ─────────────────────────────────────────────────────────────────────────
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(404, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Internal server error"));
    }
}
