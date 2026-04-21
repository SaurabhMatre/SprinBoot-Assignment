package com.social.api.dto;

import com.social.api.entity.Post;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

public class PostDtos {

    @Data
    public static class CreatePostRequest {
        @NotNull(message = "authorId is required")
        private Long authorId;

        @NotNull(message = "authorType is required")
        private Post.AuthorType authorType;

        @NotBlank(message = "content cannot be blank")
        private String content;
    }

    @Data
    public static class PostResponse {
        private Long id;
        private Long authorId;
        private Post.AuthorType authorType;
        private String content;
        private String createdAt;
        private Long viralityScore;
    }

    @Data
    public static class CreateCommentRequest {
        @NotNull(message = "authorId is required")
        private Long authorId;

        @NotNull(message = "authorType is required")
        private com.social.api.entity.Comment.AuthorType authorType;

        @NotBlank(message = "content cannot be blank")
        private String content;

        /** Depth level of this comment (0 = direct reply to post). */
        private int depthLevel = 0;

        /**
         * For BOT comments: the ID of the human who owns the post
         * (needed for cooldown check). Required when authorType = BOT.
         */
        private Long postOwnerId;
    }

    @Data
    public static class CommentResponse {
        private Long id;
        private Long postId;
        private Long authorId;
        private com.social.api.entity.Comment.AuthorType authorType;
        private String content;
        private int depthLevel;
        private String createdAt;
    }

    @Data
    public static class LikeRequest {
        @NotNull(message = "userId is required")
        private Long userId;
    }

    @Data
    public static class ViralityResponse {
        private Long postId;
        private Long viralityScore;
    }

    @Data
    public static class ErrorResponse {
        private int status;
        private String message;

        public ErrorResponse(int status, String message) {
            this.status = status;
            this.message = message;
        }
    }
}
