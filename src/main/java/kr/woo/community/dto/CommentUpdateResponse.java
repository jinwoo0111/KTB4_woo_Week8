package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.AllArgsConstructor;

// 댓글 수정 응답 DTO
@Getter
@AllArgsConstructor
public class CommentUpdateResponse {

    @JsonProperty("comment_id")
    private Long commentId;

    @JsonProperty("author_id")
    private Long authorId;

    @JsonProperty("author_nickname")
    private String authorNickname;

    @JsonProperty("author_profile_image")
    private String authorProfileImage;

    @JsonProperty("created_at")
    private String createdAt;

    private String content;
}
