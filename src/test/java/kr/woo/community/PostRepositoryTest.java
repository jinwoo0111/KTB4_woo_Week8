package kr.woo.community;

import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class PostRepositoryTest {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("조회수 증가 쿼리는 현재 값에 1을 원자적으로 더한다")
    void increaseViewCountSuccess() {
        User user = userRepository.save(
                new User("test@test.com", "password", "nickname", null)
        );
        Post post = postRepository.saveAndFlush(
                new Post("title", "content", null, user)
        );

        int updatedRowCount = postRepository.increaseViewCount(post.getId());
        Post updatedPost = postRepository.findById(post.getId()).orElseThrow();

        assertEquals(1, updatedRowCount);
        assertEquals(1, updatedPost.getViewCount());
    }

    @Test
    @DisplayName("삭제된 게시글의 조회수는 증가시키지 않는다")
    void increaseViewCountIgnoresDeletedPost() {
        User user = userRepository.save(
                new User("test2@test.com", "password", "nickname2", null)
        );
        Post post = postRepository.save(
                new Post("title", "content", null, user)
        );
        post.softDelete();
        postRepository.flush();

        int updatedRowCount = postRepository.increaseViewCount(post.getId());

        assertEquals(0, updatedRowCount);
    }

    @Test
    @DisplayName("제목 검색은 검색어가 제목에 포함된 게시글만 조회한다")
    void searchPostsByTitleMatchesTitleOnly() {
        // given
        User user = userRepository.save(
                new User("search@test.com", "password", "검색작성자", null)
        );
        Post matchingPost = postRepository.save(
                new Post("스프링 검색 기능", "JPA를 공부한다", null, user)
        );
        postRepository.save(
                new Post("JPA 학습", "스프링을 공부한다", null, user)
        );
        postRepository.flush();

        // when
        List<Post> result = postRepository.searchPostsByTitle(
                "스프링",
                null,
                PageRequest.of(0, 10)
        );

        // then
        assertEquals(1, result.size());
        assertEquals(matchingPost.getId(), result.get(0).getId());
    }
}
