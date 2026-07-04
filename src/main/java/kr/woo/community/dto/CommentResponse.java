package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CommentResponse {
    @JsonProperty("comment_id")
    private Long commentId;

    @JsonProperty("author_nickname")
    private String authorNickname;

    @JsonProperty("created_at")
    private String createdAt;
    private String content;

}
