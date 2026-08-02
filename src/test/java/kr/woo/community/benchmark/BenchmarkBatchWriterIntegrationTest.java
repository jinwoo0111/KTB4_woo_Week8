package kr.woo.community.benchmark;

import kr.woo.community.entity.Post;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class BenchmarkBatchWriterIntegrationTest {
    private static final long SEED = 20260802L;

    @Autowired
    private BenchmarkBatchWriter batchWriter;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        postRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void savesAuthorsAndPostsThroughTransactionalProxy() {
        assertThat(AopUtils.isAopProxy(batchWriter)).isTrue();

        List<Long> authorIds = batchWriter.saveAuthors(2);
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(SEED, 1_000, 2);

        batchWriter.savePostBatch(factory, 1, 10, authorIds);
        batchWriter.savePostBatch(factory, 11, 20, authorIds);

        List<Post> posts = postRepository.findAll();

        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(posts).hasSize(20);
        assertThat(posts.stream().filter(Post::isDeleted)).hasSize(1);
    }

    @Test
    void keepsCommittedBatchWhenLaterBatchRollsBack() {
        List<Long> authorIds = batchWriter.saveAuthors(1);
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(SEED, 1_000, 2);

        batchWriter.savePostBatch(factory, 1, 1, authorIds);

        assertThatThrownBy(() -> batchWriter.savePostBatch(factory, 3, 4, authorIds))
                .isInstanceOf(IndexOutOfBoundsException.class);

        assertThat(postRepository.count()).isEqualTo(1);
    }
}
