package kr.woo.community.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor
public class Post {

    @Id @GeneratedValue
    @Column(name = "post_id")
    private Long id;

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "content_image")
    private String contentImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User author;

    @Column(name = "like_count")
    private int likeCount;

    @Column(name = "comment_count")
    private int commentCount;

    @Column(name = "view_count")
    private int viewCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Post(String title, String content, String contentImage, User author) {
        this.title = title;
        this.content = content;
        this.contentImage = contentImage;
        this.author = author;
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // 댓글 수 증가
    public void increaseCommentCount() {
        this.commentCount++;
    }

    // 댓글 수 감소
    public void decreaseCommentCount() {
        if(commentCount > 0) {
            this.commentCount--;
        }
    }

    // 좋아요 수 증가
    public void increaseLikeCount() {
        likeCount++;
    }

    // 좋아요 수 감소
    public void decreaseLikeCount() {
        if (likeCount > 0) {
            likeCount--;
        }
    }

    // 제목 수정
    public void changeTitle(String title) {
        this.title = title;
    }

    // 게시글 내용 수정
    public void changeContent(String content) {
        this.content = content;
    }

    // 게시글 이미지 수정
    public void changeContentImage(String contentImage) {
        this.contentImage = contentImage;
    }

    // 게시글 삭제
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    // 삭제 여부 확인
    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
