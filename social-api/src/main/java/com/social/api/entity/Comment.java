package com.social.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false)
    private AuthorType authorType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Depth level: 0 = top-level reply to post, 1 = reply to a reply, etc.
     * Max allowed: 20.
     */
    @Column(name = "depth_level", nullable = false)
    @Builder.Default
    private Integer depthLevel = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum AuthorType {
        USER, BOT
    }
}
