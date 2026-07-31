package kr.woo.community;

import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PostSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Test
    @DisplayName("비로그인 전체 검색은 대소문자를 구분하지 않고 삭제되지 않은 제목 또는 내용 일치 게시글을 반환한다")
    void searchAllReturnsActivePostsIgnoringCase() throws Exception {
        User author = saveUser("search-integration@test.com", "통합검색작성자");
        Post titleMatchedPost = savePost("Spring 검색 기능", "JPA를 공부한다", author);
        Post contentMatchedPost = savePost("JPA 학습", "SPRING을 공부한다", author);
        Post deletedPost = savePost("삭제된 Spring 게시글", "검색되면 안 된다", author);
        deletedPost.softDelete();
        postRepository.flush();

        mockMvc.perform(get("/posts")
                        .param("keyword", "spring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("posts_success"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.has_next").value(false))
                .andExpect(jsonPath("$.data.next_cursor").doesNotExist())
                .andExpect(jsonPath("$.data.posts[0].post_id").value(contentMatchedPost.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(titleMatchedPost.getId()))
                .andExpect(jsonPath("$.data.posts[0].author").value("통합검색작성자"));
    }

    @Test
    @DisplayName("LIKE 특수문자는 와일드카드가 아니라 입력된 문자 그대로 검색한다")
    void searchTreatsLikeSpecialCharactersLiterally() throws Exception {
        User author = saveUser("special-search@test.com", "특수문자작성자");
        Post matchedPost = savePost(
                "Spring_100% C:\\Temp 사용법",
                "특수문자를 포함한 게시글",
                author
        );
        savePost("SpringX1000 CXXTemp 사용법", "와일드카드라면 검색될 게시글", author);
        postRepository.flush();

        mockMvc.perform(get("/posts")
                        .param("keyword", "SPRING_100% C:\\TEMP")
                        .param("scope", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(matchedPost.getId()));
    }

    @Test
    @DisplayName("검색 결과의 다음 페이지는 응답의 next_cursor 이후 게시글을 중복이나 누락 없이 반환한다")
    void searchUsesNextCursorForFollowingPage() throws Exception {
        User author = saveUser("cursor-integration@test.com", "통합커서작성자");
        Post oldestPost = savePost("스프링 1", "첫 번째 게시글", author);
        Post secondPost = savePost("스프링 2", "두 번째 게시글", author);
        Post thirdPost = savePost("스프링 3", "세 번째 게시글", author);
        Post newestPost = savePost("스프링 4", "네 번째 게시글", author);
        postRepository.flush();

        mockMvc.perform(get("/posts")
                        .param("keyword", "스프링")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.has_next").value(true))
                .andExpect(jsonPath("$.data.next_cursor").value(thirdPost.getId()))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(newestPost.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(thirdPost.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "스프링")
                        .param("cursor", thirdPost.getId().toString())
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.has_next").value(false))
                .andExpect(jsonPath("$.data.next_cursor").doesNotExist())
                .andExpect(jsonPath("$.data.posts[0].post_id").value(secondPost.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(oldestPost.getId()));
    }

    @Test
    @DisplayName("유효하지 않은 검색어와 검색 범위는 실제 요청 흐름에서 400 응답으로 반환된다")
    void invalidSearchRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/posts")
                        .param("keyword", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_search_keyword"));

        mockMvc.perform(get("/posts")
                        .param("keyword", "스프링")
                        .param("scope", "author"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_search_scope"));
    }

    private User saveUser(String email, String nickname) {
        return userRepository.save(
                new User(email, "password", nickname, null)
        );
    }

    private Post savePost(String title, String content, User author) {
        return postRepository.save(
                new Post(title, content, null, author)
        );
    }
}
