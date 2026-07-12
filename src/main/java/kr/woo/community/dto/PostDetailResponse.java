package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.AllArgsConstructor;

import java.util.List;

// 게시글 상세조회 API data 전체 표현하는 DTO (게시글 + 댓글)
@Getter
@AllArgsConstructor
public class PostDetailResponse {
    @JsonProperty("post_id")
    private Long postId;

    private String title;

    @JsonProperty("created_at")
    private String createdAt;

    private String author;
    private String content;

    @JsonProperty("content_image")
    private String contentImage;

    @JsonProperty("like_count")
    private int likeCount;

    @JsonProperty("liked_by_me")
    private boolean likedByMe;

    @JsonProperty("comment_count")
    private int commentCount;

    @JsonProperty("view_count")
    private int viewCount;

    private List<CommentResponse> comments;
}
