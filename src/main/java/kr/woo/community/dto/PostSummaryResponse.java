package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PostSummaryResponse {

    @JsonProperty("post_id")
    private Long postId;
    private String title;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("like_count")
    private int likeCount;

    @JsonProperty("comment_count")
    private int commentCount;

    @JsonProperty("view_count")
    private int viewCount;

    private String author;

    private String content;

    @JsonProperty("content_image")
    private String contentImage;

    @JsonProperty("author_profile_image")
    private String authorProfileImage;
}
