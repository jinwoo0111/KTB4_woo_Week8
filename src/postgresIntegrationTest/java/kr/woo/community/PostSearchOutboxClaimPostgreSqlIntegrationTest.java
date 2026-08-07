package kr.woo.community;

import kr.woo.community.dto.PostCreateRequest;
import kr.woo.community.entity.User;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.search.outbox.ClaimedPostSearchOutboxEvent;
import kr.woo.community.search.outbox.PostSearchOutboxClaimService;
import kr.woo.community.search.outbox.PostSearchOutboxEvent;
import kr.woo.community.search.outbox.PostSearchOutboxRepository;
import kr.woo.community.search.outbox.PostSearchOutboxStatus;
import kr.woo.community.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgres-integration-test")
class PostSearchOutboxClaimPostgreSqlIntegrationTest {

    @Autowired
    private PostSearchOutboxRepository outboxRepository;

    @Autowired
    private PostSearchOutboxClaimService claimService;

    @Autowired
    private PostService postService;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void skipsAnEventLockedByAnotherTransactionAndClaimsTheNextOne()
            throws Exception {
        User author = userRepository.saveAndFlush(
                new User("skip-locked@test.com", "password", "선점PG작성자", null)
        );
        postService.createPost(author.getId(), createRequest("첫 이벤트"));
        postService.createPost(author.getId(), createRequest("둘째 이벤트"));

        CountDownLatch firstRowLocked = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        Future<Long> lockedEventFuture = executor.submit(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    List<PostSearchOutboxEvent> locked =
                            outboxRepository.findClaimableForUpdateSkipLocked(
                                    PostSearchOutboxStatus.PENDING,
                                    LocalDateTime.now(),
                                    PageRequest.of(0, 1)
                            );
                    Long eventId = locked.getFirst().getId();
                    firstRowLocked.countDown();
                    try {
                        if (!releaseFirstLock.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out holding the first lock");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return eventId;
                })
        );

        assertThat(firstRowLocked.await(5, TimeUnit.SECONDS)).isTrue();
        Future<List<ClaimedPostSearchOutboxEvent>> secondWorkerFuture =
                executor.submit(() -> claimService.claimBatch("worker-b", 1));

        List<ClaimedPostSearchOutboxEvent> secondWorkerClaim;
        try {
            secondWorkerClaim = secondWorkerFuture.get(3, TimeUnit.SECONDS);
        } finally {
            releaseFirstLock.countDown();
        }
        Long lockedEventId = lockedEventFuture.get(5, TimeUnit.SECONDS);

        assertThat(secondWorkerClaim).hasSize(1);
        assertThat(secondWorkerClaim.getFirst().eventId())
                .isGreaterThan(lockedEventId);
    }

    private PostCreateRequest createRequest(String title) {
        try {
            return objectMapper.readValue(
                    "{\"title\":\"" + title + "\",\"content\":\"본문\"}",
                    PostCreateRequest.class
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
