package kr.woo.community;

import kr.woo.community.controller.PostController;
import kr.woo.community.security.config.SecurityConfig;
import kr.woo.community.security.jwt.JWTUtil;
import kr.woo.community.dto.PostViewResponse;
import kr.woo.community.service.PostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

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

    @Test
    @DisplayName("비로그인 사용자는 게시글 상세를 조회할 수 있다")
    @WithAnonymousUser
    void getPostDetailWithoutLoginSuccess() throws Exception {
        mockMvc.perform(get("/posts/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비로그인 사용자도 게시글 조회수를 증가시킬 수 있다")
    @WithAnonymousUser
    void increaseViewCountWithoutLoginSuccess() throws Exception {
        when(postService.increaseViewCount(1L)).thenReturn(new PostViewResponse(11));

        mockMvc.perform(post("/posts/1/views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_view_increase_success"))
                .andExpect(jsonPath("$.data.view_count").value(11));
    }
}
