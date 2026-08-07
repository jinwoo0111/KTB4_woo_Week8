package kr.woo.community.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import kr.woo.community.dto.PostCreateRequest;
import kr.woo.community.dto.PostUpdateRequest;
import kr.woo.community.entity.User;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.search.index.PostSearchIndexInitializer;
import kr.woo.community.search.index.PostSearchIndexNames;
import kr.woo.community.search.outbox.ClaimedPostSearchOutboxEvent;
import kr.woo.community.search.outbox.PostSearchIndexingResult;
import kr.woo.community.search.outbox.PostSearchOutboxEvent;
import kr.woo.community.search.outbox.PostSearchOutboxEventType;
import kr.woo.community.search.outbox.PostSearchOutboxIndexer;
import kr.woo.community.search.outbox.PostSearchOutboxPayload;
import kr.woo.community.search.outbox.PostSearchOutboxProcessor;
import kr.woo.community.search.outbox.PostSearchOutboxRepository;
import kr.woo.community.search.outbox.PostSearchOutboxStatus;
import kr.woo.community.search.query.ElasticsearchPostSearchGateway;
import kr.woo.community.search.query.PostSearchCandidate;
import kr.woo.community.search.query.PostSearchCriteria;
import kr.woo.community.search.query.PostSearchScope;
import kr.woo.community.search.query.PostSearchSort;
import kr.woo.community.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ElasticsearchTestcontainersConfiguration.class)
@ActiveProfiles("test")
class PostSearchOutboxIndexerIntegrationTest {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ElasticsearchPostSearchGateway searchGateway;

    @Autowired
    private PostSearchOutboxIndexer indexer;

    @Autowired
    private PostSearchOutboxProcessor processor;

    @Autowired
    private PostSearchOutboxRepository outboxRepository;

    @Autowired
    private PostService postService;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        deleteInitialIndexIfPresent();
        new PostSearchIndexInitializer(elasticsearchClient).initialize();
        outboxRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteInitialIndexIfPresent();
    }

    @Test
    void asynchronouslyAppliesCreateUpdateAndSoftDeleteEvents() throws Exception {
        User author = saveUser("outbox-es@test.com", "ES동기화작성자");
        var created = postService.createPost(
                author.getId(),
                createRequest("outboxinitialmarker 대한민국 개발자", "초기 본문")
        );

        assertThat(processor.processBatch("integration-worker")).isEqualTo(1);
        refresh();
        assertThat(searchIds("outboxinitialmarker"))
                .containsExactly(created.getPostId());
        assertLatestEventProcessedWithOneAttempt();

        postService.updatePost(
                created.getPostId(),
                author.getId(),
                updateRequest("outboxupdatedmarker 대한민국 개발자", "수정 본문")
        );
        assertThat(processor.processBatch("integration-worker")).isEqualTo(1);
        refresh();
        assertThat(searchIds("outboxinitialmarker")).isEmpty();
        assertThat(searchIds("outboxupdatedmarker"))
                .containsExactly(created.getPostId());
        assertLatestEventProcessedWithOneAttempt();

        postService.deletePost(created.getPostId(), author.getId());
        assertThat(processor.processBatch("integration-worker")).isEqualTo(1);
        refresh();
        assertThat(searchIds("outboxupdatedmarker")).isEmpty();
        assertThat(elasticsearchClient.exists(request -> request
                .index(PostSearchIndexNames.READ_ALIAS)
                .id(created.getPostId().toString())).value()).isFalse();
        assertLatestEventProcessedWithOneAttempt();
    }

    @Test
    void externalVersionMakesDuplicatesIdempotentAndRejectsOlderEvents()
            throws Exception {
        long postId = 900L;
        ClaimedPostSearchOutboxEvent newer = upsertEvent(
                200L,
                postId,
                "newerversionmarker 최신 제목"
        );
        ClaimedPostSearchOutboxEvent older = upsertEvent(
                100L,
                postId,
                "olderversionmarker 과거 제목"
        );

        assertThat(indexer.apply(newer)).isEqualTo(PostSearchIndexingResult.APPLIED);
        assertThat(indexer.apply(newer)).isEqualTo(PostSearchIndexingResult.APPLIED);
        assertThat(indexer.apply(older)).isEqualTo(PostSearchIndexingResult.STALE);
        refresh();
        assertThat(searchIds("newerversionmarker")).containsExactly(postId);
        assertThat(searchIds("olderversionmarker")).isEmpty();

        ClaimedPostSearchOutboxEvent delete = deleteEvent(300L, postId);
        assertThat(indexer.apply(delete)).isEqualTo(PostSearchIndexingResult.APPLIED);
        assertThat(indexer.apply(upsertEvent(
                250L,
                postId,
                "resurrectionmarker 되살아나면 안 됨"
        ))).isEqualTo(PostSearchIndexingResult.STALE);
        refresh();
        assertThat(searchIds("resurrectionmarker")).isEmpty();
    }

    @Test
    void deleteOfAMissingDocumentIsIdempotentAndKeepsAnExternalVersionTombstone()
            throws Exception {
        long postId = 901L;

        assertThat(indexer.apply(deleteEvent(400L, postId)))
                .isEqualTo(PostSearchIndexingResult.APPLIED);
        assertThat(indexer.apply(upsertEvent(
                350L,
                postId,
                "missingtombstonemarker 오래된 생성"
        ))).isEqualTo(PostSearchIndexingResult.STALE);
        refresh();
        assertThat(searchIds("missingtombstonemarker")).isEmpty();
    }

    @Test
    void missingWriteAliasLeavesTheEventPendingForRetryWithoutCreatingAnIndex()
            throws Exception {
        User author = saveUser("outbox-alias@test.com", "Alias실패작성자");
        postService.createPost(
                author.getId(),
                createRequest("aliasfailuremarker 제목", "본문")
        );
        elasticsearchClient.indices().deleteAlias(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
                .name(PostSearchIndexNames.WRITE_ALIAS));

        assertThat(processor.processBatch("integration-worker")).isEqualTo(1);

        PostSearchOutboxEvent event = outboxRepository
                .findAllByOrderByIdAsc()
                .getFirst();
        assertThat(event.getStatus()).isEqualTo(PostSearchOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isOne();
        assertThat(event.getLastError())
                .contains("PostSearchOutboxIndexingException");
        assertThat(elasticsearchClient.indices().exists(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)).value()).isTrue();
        assertThat(elasticsearchClient.indices().exists(request -> request
                .index(PostSearchIndexNames.WRITE_ALIAS)).value()).isFalse();
    }

    private User saveUser(String email, String nickname) {
        return userRepository.saveAndFlush(
                new User(email, "password", nickname, null)
        );
    }

    private PostCreateRequest createRequest(String title, String content)
            throws Exception {
        return objectMapper.readValue(
                "{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}",
                PostCreateRequest.class
        );
    }

    private PostUpdateRequest updateRequest(String title, String content)
            throws Exception {
        return objectMapper.readValue(
                "{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}",
                PostUpdateRequest.class
        );
    }

    private ClaimedPostSearchOutboxEvent upsertEvent(
            long eventId,
            long postId,
            String title
    ) throws Exception {
        PostSearchOutboxPayload payload = new PostSearchOutboxPayload(
                postId,
                title,
                "외부 버전 검증 본문",
                LocalDateTime.of(2026, 8, 7, 10, 0).toString(),
                null
        );
        return new ClaimedPostSearchOutboxEvent(
                eventId,
                postId,
                PostSearchOutboxEventType.UPSERT,
                PostSearchOutboxEvent.CURRENT_PAYLOAD_VERSION,
                objectMapper.writeValueAsString(payload),
                1
        );
    }

    private ClaimedPostSearchOutboxEvent deleteEvent(long eventId, long postId)
            throws Exception {
        return new ClaimedPostSearchOutboxEvent(
                eventId,
                postId,
                PostSearchOutboxEventType.DELETE,
                PostSearchOutboxEvent.CURRENT_PAYLOAD_VERSION,
                objectMapper.writeValueAsString(PostSearchOutboxPayload.delete(postId)),
                1
        );
    }

    private List<Long> searchIds(String keyword) {
        return searchGateway.search(new PostSearchCriteria(
                        keyword,
                        PostSearchScope.TITLE,
                        PostSearchSort.TIME,
                        10
                )).stream()
                .map(PostSearchCandidate::postId)
                .toList();
    }

    private void assertLatestEventProcessedWithOneAttempt() {
        PostSearchOutboxEvent event = outboxRepository
                .findAllByOrderByIdAsc()
                .getLast();
        assertThat(event.getStatus()).isEqualTo(PostSearchOutboxStatus.PROCESSED);
        assertThat(event.getAttemptCount()).isOne();
        assertThat(event.getProcessedAt()).isNotNull();
        assertThat(event.getClaimedAt()).isNull();
        assertThat(event.getClaimedBy()).isNull();
        assertThat(event.getLastError()).isNull();
    }

    private void refresh() throws Exception {
        elasticsearchClient.indices().refresh(request -> request
                .index(PostSearchIndexNames.READ_ALIAS));
    }

    private void deleteInitialIndexIfPresent() throws Exception {
        boolean exists = elasticsearchClient.indices().exists(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)).value();
        if (exists) {
            elasticsearchClient.indices().delete(request -> request
                    .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX));
        }
    }
}
