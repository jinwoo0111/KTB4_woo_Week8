package kr.woo.community;

import kr.woo.community.controller.PostController;
import kr.woo.community.security.config.SecurityConfig;
import kr.woo.community.security.jwt.JWTUtil;
import kr.woo.community.service.PostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PostController.class)
@Import(SecurityConfig.class)
class PostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostService postService;

    @MockitoBean
    private JWTUtil jwtUtil;

    @Test
    @DisplayName("비로그인 사용자는 게시글 목록을 조회할 수 있다")
    @WithAnonymousUser
    void getPostsWithoutLoginSuccess() throws Exception {
        mockMvc.perform(get("/posts"))
                .andExpect(status().isOk());
    }
}