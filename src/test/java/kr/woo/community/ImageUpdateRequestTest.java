package kr.woo.community;

import kr.woo.community.dto.PostUpdateRequest;
import kr.woo.community.dto.UserUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageUpdateRequestTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("게시글 이미지 제거 필드를 snake_case JSON으로 받는다")
    void deserializePostImageRemovalField() throws Exception {
        PostUpdateRequest request = objectMapper.readValue(
                "{\"remove_content_image\":true}",
                PostUpdateRequest.class
        );

        assertTrue(request.isRemoveContentImage());
    }

    @Test
    @DisplayName("프로필 이미지 제거 필드를 snake_case JSON으로 받는다")
    void deserializeProfileImageRemovalField() throws Exception {
        UserUpdateRequest request = objectMapper.readValue(
                "{\"remove_profile_image\":true}",
                UserUpdateRequest.class
        );

        assertTrue(request.isRemoveProfileImage());
    }
}
