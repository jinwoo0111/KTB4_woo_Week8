package kr.woo.community.search.outbox;

import kr.woo.community.dto.PostCreateRequest;
import kr.woo.community.entity.User;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class PostSearchOutboxFailureRollbackIntegrationTest {

    @Autowired
    private PostService postService;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PostSearchOutboxWriter outboxWriter;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void rollsBackTheSourcePostWhenOutboxRecordingFails() throws Exception {
        User author = userRepository.saveAndFlush(
                new User("outbox-failure@test.com", "password", "실패작성자", null)
        );
        PostCreateRequest request = objectMapper.readValue(
                "{\"title\":\"원자성 제목\",\"content\":\"원자성 본문\"}",
                PostCreateRequest.class
        );
        long postsBefore = postRepository.count();
        doThrow(new IllegalStateException("forced outbox failure"))
                .when(outboxWriter).recordUpsert(any());

        assertThatThrownBy(() -> postService.createPost(author.getId(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced outbox failure");

        assertThat(postRepository.count()).isEqualTo(postsBefore);
    }
}
