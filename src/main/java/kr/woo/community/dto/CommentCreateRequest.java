package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 댓글 입력(생성) 요청 DTO
@Getter
@NoArgsConstructor
public class CommentCreateRequest {
    @NotBlank
    private String content;
}
