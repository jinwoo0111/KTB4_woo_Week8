package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 게시글 수정 요청 DTO
@Getter
@NoArgsConstructor
public class PostUpdateRequest {
    private String title;

    @Size(max = 32_000)
    private String content;

    @JsonProperty("content_image")
    private String contentImage;

    @JsonProperty("remove_content_image")
    private boolean removeContentImage;
}
