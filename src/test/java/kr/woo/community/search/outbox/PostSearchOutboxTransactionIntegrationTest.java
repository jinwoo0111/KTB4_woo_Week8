package kr.woo.community.search.outbox;

import kr.woo.community.dto.PostCreateRequest;
import kr.woo.community.dto.PostUpdateRequest;
import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PostSearchOutboxTransactionIntegrationTest {

    @Autowired
    private PostService postService;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostSearchOutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PostSearchOutboxClaimService claimService;

    @Autowired
    private PostSearchOutboxStateService stateService;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void recordsACommittedCreateAsAPendingVersionedUpsertSnapshot() throws Exception {
        User author = saveUser("outbox-create@test.com", "생성작성자");

        var response = postService.createPost(
                author.getId(),
                createRequest("대한민국 개발자 커뮤니티", "생성 본문")
        );

        List<PostSearchOutboxEvent> events = outboxRepository.findAllByOrderByIdAsc();
        assertThat(events).hasSize(1);
        PostSearchOutboxEvent event = events.getFirst();
        PostSearchOutboxPayload payload = readPayload(event);

        assertThat(event.getId()).isPositive();
        assertThat(event.getAggregateId()).isEqualTo(response.getPostId());
        assertThat(event.getEventType()).isEqualTo(PostSearchOutboxEventType.UPSERT);
        assertThat(event.getPayloadVersion())
                .isEqualTo(PostSearchOutboxEvent.CURRENT_PAYLOAD_VERSION);
        assertThat(event.getStatus()).isEqualTo(PostSearchOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getAvailableAt()).isNotNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getClaimedAt()).isNull();
        assertThat(event.getProcessedAt()).isNull();
        assertThat(event.getLastError()).isNull();
        assertThat(payload.postId()).isEqualTo(response.getPostId());
        assertThat(payload.title()).isEqualTo("대한민국 개발자 커뮤니티");
        assertThat(payload.content()).isEqualTo("생성 본문");
        assertThat(payload.createdAt()).isNotBlank();
        assertThat(payload.updatedAt()).isNull();
    }

    @Test
    void recordsAnUpsertOnlyWhenTitleOrContentActuallyChanges() throws Exception {
        User author = saveUser("outbox-update@test.com", "수정작성자");
        Post post = postRepository.saveAndFlush(
                new Post("기존 제목", "기존 본문", null, author)
        );

        postService.updatePost(
                post.getId(),
                author.getId(),
                updateRequest("변경 제목", "변경 본문", null)
        );

        List<PostSearchOutboxEvent> events = outboxRepository.findAllByOrderByIdAsc();
        assertThat(events).hasSize(1);
        PostSearchOutboxPayload payload = readPayload(events.getFirst());
        assertThat(events.getFirst().getEventType())
                .isEqualTo(PostSearchOutboxEventType.UPSERT);
        assertThat(payload.postId()).isEqualTo(post.getId());
        assertThat(payload.title()).isEqualTo("변경 제목");
        assertThat(payload.content()).isEqualTo("변경 본문");
        assertThat(payload.createdAt()).isNotBlank();
        assertThat(payload.updatedAt()).isNotBlank();

        outboxRepository.deleteAll();
        postService.updatePost(
                post.getId(),
                author.getId(),
                updateRequest("변경 제목", "변경 본문", "/uploads/new.png")
        );
        postService.increaseViewCount(post.getId());

        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void assignsIncreasingEventIdsAndKeepsEarlierSnapshotsImmutable() throws Exception {
        User author = saveUser("outbox-order@test.com", "순서작성자");
        var created = postService.createPost(
                author.getId(),
                createRequest("첫 제목", "첫 본문")
        );
        postService.updatePost(
                created.getPostId(),
                author.getId(),
                updateRequest("둘째 제목", "둘째 본문", null)
        );
        postService.deletePost(created.getPostId(), author.getId());

        List<PostSearchOutboxEvent> events = outboxRepository.findAllByOrderByIdAsc();
        assertThat(events).extracting(PostSearchOutboxEvent::getEventType)
                .containsExactly(
                        PostSearchOutboxEventType.UPSERT,
                        PostSearchOutboxEventType.UPSERT,
                        PostSearchOutboxEventType.DELETE
                );
        assertThat(events.get(0).getId()).isLessThan(events.get(1).getId());
        assertThat(events.get(1).getId()).isLessThan(events.get(2).getId());
        assertThat(events).extracting(PostSearchOutboxEvent::getAggregateId)
                .containsOnly(created.getPostId());
        assertThat(readPayload(events.get(0)).title()).isEqualTo("첫 제목");
        assertThat(readPayload(events.get(1)).title()).isEqualTo("둘째 제목");
        assertThat(readPayload(events.get(2)).title()).isNull();
    }

    @Test
    void recordsSoftDeleteAsAMinimalDeletePayload() throws Exception {
        User author = saveUser("outbox-delete@test.com", "삭제작성자");
        Post post = postRepository.saveAndFlush(
                new Post("삭제 제목", "삭제 본문", null, author)
        );

        postService.deletePost(post.getId(), author.getId());

        List<PostSearchOutboxEvent> events = outboxRepository.findAllByOrderByIdAsc();
        assertThat(events).hasSize(1);
        PostSearchOutboxEvent event = events.getFirst();
        PostSearchOutboxPayload payload = readPayload(event);
        assertThat(event.getAggregateId()).isEqualTo(post.getId());
        assertThat(event.getEventType()).isEqualTo(PostSearchOutboxEventType.DELETE);
        assertThat(payload.postId()).isEqualTo(post.getId());
        assertThat(payload.title()).isNull();
        assertThat(payload.content()).isNull();
        assertThat(payload.createdAt()).isNull();
        assertThat(payload.updatedAt()).isNull();
        assertThat(event.getPayload()).isEqualTo("{\"post_id\":" + post.getId() + "}");
    }

    @Test
    void rollsBackThePostAndOutboxEventTogether() {
        User author = saveUser("outbox-rollback@test.com", "롤백작성자");
        long postsBefore = postRepository.count();
        long eventsBefore = outboxRepository.count();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            postService.createPost(
                    author.getId(),
                    createRequest("롤백 제목", "롤백 본문")
            );
            throw new IllegalStateException("force rollback after both writes");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback after both writes");

        assertThat(postRepository.count()).isEqualTo(postsBefore);
        assertThat(outboxRepository.count()).isEqualTo(eventsBefore);
    }

    @Test
    void claimsPendingEventsInOrderAndOnlyTheOwningWorkerCanCompleteThem() {
        User author = saveUser("outbox-claim@test.com", "선점작성자");
        postService.createPost(author.getId(), createRequest("첫 이벤트", "첫 본문"));
        postService.createPost(author.getId(), createRequest("둘째 이벤트", "둘째 본문"));

        List<ClaimedPostSearchOutboxEvent> firstClaim =
                claimService.claimBatch("worker-a", 1);

        assertThat(firstClaim).hasSize(1);
        PostSearchOutboxEvent first = outboxRepository
                .findById(firstClaim.getFirst().eventId())
                .orElseThrow();
        assertThat(first.getStatus()).isEqualTo(PostSearchOutboxStatus.PROCESSING);
        assertThat(first.getAttemptCount()).isOne();
        assertThat(first.getClaimedBy()).isEqualTo("worker-a");
        assertThat(stateService.markProcessed(first.getId(), "worker-b")).isFalse();
        assertThat(stateService.markProcessed(first.getId(), "worker-a")).isTrue();

        List<ClaimedPostSearchOutboxEvent> secondClaim =
                claimService.claimBatch("worker-b", 1);
        assertThat(secondClaim).hasSize(1);
        assertThat(secondClaim.getFirst().eventId()).isGreaterThan(first.getId());
    }

    @Test
    void failedAttemptReturnsToPendingWithBackoffAndPreservesTheError() {
        User author = saveUser("outbox-retry@test.com", "재시작성자");
        postService.createPost(author.getId(), createRequest("재시도 이벤트", "재시도 본문"));
        ClaimedPostSearchOutboxEvent claimed =
                claimService.claimBatch("worker-a", 1).getFirst();

        boolean updated = stateService.markFailed(
                claimed.eventId(),
                "worker-a",
                new IllegalStateException("Elasticsearch unavailable")
        );

        PostSearchOutboxEvent failed = outboxRepository
                .findById(claimed.eventId())
                .orElseThrow();
        assertThat(updated).isTrue();
        assertThat(failed.getStatus()).isEqualTo(PostSearchOutboxStatus.PENDING);
        assertThat(failed.getAttemptCount()).isOne();
        assertThat(failed.getAvailableAt()).isAfter(failed.getClaimedAt() == null
                ? failed.getCreatedAt()
                : failed.getClaimedAt());
        assertThat(failed.getClaimedBy()).isNull();
        assertThat(failed.getLastError())
                .contains("IllegalStateException")
                .contains("Elasticsearch unavailable");
        assertThat(claimService.claimBatch("worker-b", 1)).isEmpty();
    }

    @Test
    void doesNotClaimANewerEventForTheSamePostUntilTheOlderEventCompletes() {
        User author = saveUser("outbox-aggregate-order@test.com", "집계순서작성자");
        var created = postService.createPost(
                author.getId(),
                createRequest("첫 snapshot", "첫 본문")
        );
        postService.updatePost(
                created.getPostId(),
                author.getId(),
                updateRequest("둘째 snapshot", "둘째 본문", null)
        );

        ClaimedPostSearchOutboxEvent first =
                claimService.claimBatch("worker-a", 1).getFirst();
        assertThat(claimService.claimBatch("worker-b", 1)).isEmpty();

        assertThat(stateService.markProcessed(first.eventId(), "worker-a")).isTrue();
        List<ClaimedPostSearchOutboxEvent> second =
                claimService.claimBatch("worker-b", 1);

        assertThat(second).hasSize(1);
        assertThat(second.getFirst().aggregateId()).isEqualTo(first.aggregateId());
        assertThat(second.getFirst().eventId()).isGreaterThan(first.eventId());
    }

    private User saveUser(String email, String nickname) {
        return userRepository.saveAndFlush(new User(email, "password", nickname, null));
    }

    private PostCreateRequest createRequest(String title, String content) {
        try {
            return objectMapper.readValue(
                    "{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}",
                    PostCreateRequest.class
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PostUpdateRequest updateRequest(
            String title,
            String content,
            String contentImage
    ) {
        try {
            String image = contentImage == null
                    ? ""
                    : ",\"content_image\":\"" + contentImage + "\"";
            return objectMapper.readValue(
                    "{\"title\":\"" + title + "\",\"content\":\""
                            + content + "\"" + image + "}",
                    PostUpdateRequest.class
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PostSearchOutboxPayload readPayload(PostSearchOutboxEvent event)
            throws Exception {
        return objectMapper.readValue(event.getPayload(), PostSearchOutboxPayload.class);
    }
}
