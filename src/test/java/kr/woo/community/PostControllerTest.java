package kr.woo.community;

import kr.woo.community.controller.PostController;
import kr.woo.community.security.config.SecurityConfig;
import kr.woo.community.security.jwt.JWTUtil;
import kr.woo.community.dto.PostListRequest;
import kr.woo.community.dto.PostListResponse;
import kr.woo.community.dto.PostSearchMetadataResponse;
import kr.woo.community.dto.PostViewResponse;
import kr.woo.community.exception.InvalidSearchKeywordException;
import kr.woo.community.exception.InvalidSearchScopeException;
import kr.woo.community.exception.InvalidSearchSortException;
import kr.woo.community.service.PostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@WebMvcTest(PostController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5173"
})
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
        when(postService.getPosts(any(PostListRequest.class)))
                .thenReturn(new PostListResponse(List.of(), 0, false, null));

        mockMvc.perform(get("/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("posts_success"))
                .andExpect(jsonPath("$.data.search").doesNotExist());

        ArgumentCaptor<PostListRequest> requestCaptor =
                ArgumentCaptor.forClass(PostListRequest.class);
        verify(postService).getPosts(requestCaptor.capture());

        PostListRequest request = requestCaptor.getValue();
        assertNull(request.getKeyword());
        assertNull(request.getScope());
        assertNull(request.getSort());
        assertNull(request.getCursor());
        assertEquals(10, request.getSize());
    }

    @Test
    @DisplayName("검색 응답은 실제 백엔드와 정렬 상태를 search 메타데이터로 반환한다")
    @WithAnonymousUser
    void getPostsReturnsSearchMetadata() throws Exception {
        PostSearchMetadataResponse search = new PostSearchMetadataResponse(
                "relevance",
                "time",
                "postgres",
                false
        );
        when(postService.getPosts(any(PostListRequest.class)))
                .thenReturn(new PostListResponse(List.of(), 0, false, null, search));

        mockMvc.perform(get("/posts")
                        .param("keyword", "스프링")
                        .param("sort", "relevance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.requested_sort").value("relevance"))
                .andExpect(jsonPath("$.data.search.effective_sort").value("time"))
                .andExpect(jsonPath("$.data.search.backend").value("postgres"))
                .andExpect(jsonPath("$.data.search.degraded").value(false));
    }

    @Test
    @DisplayName("게시글 목록 검색 파라미터를 요청 DTO로 바인딩해 Service에 전달한다")
    @WithAnonymousUser
    void getPostsBindsSearchParametersToRequest() throws Exception {
        when(postService.getPosts(any(PostListRequest.class)))
                .thenReturn(new PostListResponse(List.of(), 0, false, null));

        mockMvc.perform(get("/posts")
                        .param("keyword", "Spring")
                        .param("scope", "title")
                        .param("sort", "relevance")
                        .param("cursor", "100")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("posts_success"));

        ArgumentCaptor<PostListRequest> requestCaptor =
                ArgumentCaptor.forClass(PostListRequest.class);
        verify(postService).getPosts(requestCaptor.capture());

        PostListRequest request = requestCaptor.getValue();
        assertEquals("Spring", request.getKeyword());
        assertEquals("title", request.getScope());
        assertEquals("relevance", request.getSort());
        assertEquals("100", request.getCursor());
        assertEquals(5, request.getSize());
    }

    @Test
    @DisplayName("페이지 크기에 문자열을 전달하면 잘못된 요청을 반환한다")
    void getPostsRejectsInvalidSizeType() throws Exception {
        mockMvc.perform(get("/posts")
                        .param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_request"));

        verify(postService, never()).getPosts(any(PostListRequest.class));
    }

    @Test
    @DisplayName("문자열 검색 커서를 요청 DTO로 바인딩한다")
    void getPostsBindsOpaqueCursorToRequest() throws Exception {
        when(postService.getPosts(any(PostListRequest.class)))
                .thenReturn(new PostListResponse(List.of(), 0, false, null));

        mockMvc.perform(get("/posts")
                        .param("keyword", "스프링")
                        .param("cursor", "opaque-token"))
                .andExpect(status().isOk());

        ArgumentCaptor<PostListRequest> requestCaptor =
                ArgumentCaptor.forClass(PostListRequest.class);
        verify(postService).getPosts(requestCaptor.capture());
        assertEquals("opaque-token", requestCaptor.getValue().getCursor());
    }

    @ParameterizedTest
    @MethodSource("invalidSearchExceptions")
    @DisplayName("검색 요청 오류를 400과 검색 오류 메시지로 반환한다")
    void getPostsReturnsBadRequestForSearchException(
            RuntimeException exception,
            String expectedMessage
    ) throws Exception {
        when(postService.getPosts(any(PostListRequest.class)))
                .thenThrow(exception);

        mockMvc.perform(get("/posts")
                        .param("keyword", "스프링"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(expectedMessage));
    }

    private static Stream<Arguments> invalidSearchExceptions() {
        return Stream.of(
                Arguments.of(
                        new InvalidSearchKeywordException(),
                        "invalid_search_keyword"
                ),
                Arguments.of(
                        new InvalidSearchScopeException(),
                        "invalid_search_scope"
                ),
                Arguments.of(
                        new InvalidSearchSortException(),
                        "invalid_search_sort"
                )
        );
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

    @Test
    @DisplayName("허용된 origin의 CORS preflight 요청을 승인한다")
    void allowCorsPreflightFromConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/posts")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header(
                                "Access-Control-Request-Headers",
                                "authorization,content-type"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://localhost:5173"
                ))
                .andExpect(header().string(
                        "Access-Control-Allow-Methods",
                        containsString("PATCH")
                ))
                .andExpect(header().string(
                        "Access-Control-Allow-Headers",
                        containsString("authorization")
                ))
                .andExpect(header().string(
                        "Access-Control-Allow-Headers",
                        containsString("content-type")
                ));
    }

    @Test
    @DisplayName("설정되지 않은 origin의 CORS preflight 요청을 거부한다")
    void rejectCorsPreflightFromUnknownOrigin() throws Exception {
        mockMvc.perform(options("/posts")
                        .header("Origin", "http://localhost:5500")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
