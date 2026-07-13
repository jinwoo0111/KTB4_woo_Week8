package kr.woo.community.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 댓글 입력(생성) 요청 DTO
@Getter
@NoArgsConstructor
public class CommentCreateRequest {
    @NotBlank
    private String content;
}
