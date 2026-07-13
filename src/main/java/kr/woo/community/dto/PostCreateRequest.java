package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 게시글 생성 요청 DTO
@Getter
@NoArgsConstructor
public class PostCreateRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String content;

    @JsonProperty("content_image")
    private String contentImage;

}
