package kr.woo.community.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import kr.woo.community.search.document.PostSearchDocument;
import kr.woo.community.search.index.PostSearchIndexInitializer;
import kr.woo.community.search.index.PostSearchIndexNames;
import kr.woo.community.search.query.DecodedPostSearchCursor;
import kr.woo.community.search.query.ElasticsearchPostSearchGateway;
import kr.woo.community.search.query.InvalidPostSearchCursorException;
import kr.woo.community.search.query.PostSearchCandidate;
import kr.woo.community.search.query.PostSearchCriteria;
import kr.woo.community.search.query.PostSearchCursorCodec;
import kr.woo.community.search.query.PostSearchExecutionException;
import kr.woo.community.search.query.PostSearchPage;
import kr.woo.community.search.query.PostSearchScope;
import kr.woo.community.search.query.PostSearchSort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ElasticsearchTestcontainersConfiguration.class)
@ActiveProfiles("test")
class PostSearchPitCursorIntegrationTest {

    private static final LocalDateTime CREATED_AT =
            LocalDateTime.of(2026, 8, 6, 10, 0);

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ElasticsearchPostSearchGateway searchGateway;

    @Autowired
    private PostSearchCursorCodec cursorCodec;

    @BeforeEach
    void setUp() throws Exception {
        deleteInitialIndexIfPresent();
        new PostSearchIndexInitializer(elasticsearchClient).initialize();
        for (long postId = 101; postId <= 105; postId++) {
            indexPageDocument(postId);
        }
        refresh();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteInitialIndexIfPresent();
    }

    @Test
    void continuesTimePagesWithPitAndSearchAfterWithoutSeeingLaterWrites()
            throws Exception {
        PostSearchCriteria criteria = criteria(PostSearchSort.TIME);
        PostSearchPage first = searchGateway.searchPage(criteria, null);

        assertThat(first.candidates()).extracting(PostSearchCandidate::postId)
                .containsExactly(105L, 104L);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.nextCursor()).isNotBlank().contains(".");
        assertThat(first.nextCursor()).doesNotMatch("\\d+");

        DecodedPostSearchCursor decoded = cursorCodec.decode(
                first.nextCursor(),
                criteria
        );
        assertThat(decoded.sortValues().relevanceScore()).isNull();
        assertThat(decoded.sortValues().postId()).isEqualTo(104L);
        assertThat(decoded.sortValues().pitShardDoc()).isNotNull();

        indexPageDocument(999L);
        refresh();

        List<Long> remainingIds = collectRemainingIds(criteria, first.nextCursor());
        assertThat(remainingIds).containsExactly(103L, 102L, 101L);
        assertThat(remainingIds).doesNotContain(999L);
    }

    @Test
    void continuesEqualRelevancePagesUsingScorePostIdAndPitShardDoc() {
        PostSearchCriteria criteria = criteria(PostSearchSort.RELEVANCE);
        String cursor = null;
        List<Long> ids = new ArrayList<>();

        do {
            PostSearchPage page = searchGateway.searchPage(criteria, cursor);
            ids.addAll(page.candidates().stream()
                    .map(PostSearchCandidate::postId)
                    .toList());
            cursor = page.nextCursor();
            if (cursor != null) {
                DecodedPostSearchCursor decoded = cursorCodec.decode(cursor, criteria);
                assertThat(decoded.sortValues().relevanceScore()).isNotNull();
                assertThat(decoded.sortValues().pitShardDoc()).isNotNull();
            }
        } while (cursor != null);

        assertThat(ids).containsExactly(105L, 104L, 103L, 102L, 101L);
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void rejectsAConditionMismatchBeforeReusingThePit() {
        PostSearchCriteria original = criteria(PostSearchSort.TIME);
        PostSearchPage first = searchGateway.searchPage(original, null);

        PostSearchCriteria differentKeyword = new PostSearchCriteria(
                "differentmarker",
                PostSearchScope.ALL,
                PostSearchSort.TIME,
                2
        );
        PostSearchCriteria differentScope = new PostSearchCriteria(
                "pagemarker",
                PostSearchScope.TITLE,
                PostSearchSort.TIME,
                2
        );
        PostSearchCriteria differentSort = criteria(PostSearchSort.RELEVANCE);

        assertInvalidReuse(first.nextCursor(), differentKeyword);
        assertInvalidReuse(first.nextCursor(), differentScope);
        assertInvalidReuse(first.nextCursor(), differentSort);
    }

    @Test
    void failsWhenTheCursorPitWasAlreadyClosed() throws Exception {
        PostSearchCriteria criteria = criteria(PostSearchSort.TIME);
        PostSearchPage first = searchGateway.searchPage(criteria, null);
        DecodedPostSearchCursor decoded = cursorCodec.decode(
                first.nextCursor(),
                criteria
        );
        elasticsearchClient.closePointInTime(request -> request.id(decoded.pitId()));

        assertThatThrownBy(() -> searchGateway.searchPage(criteria, first.nextCursor()))
                .isInstanceOf(PostSearchExecutionException.class);
    }

    @Test
    void closesThePitAfterTheLastPage() throws Exception {
        PostSearchCriteria criteria = criteria(PostSearchSort.TIME);
        PostSearchPage first = searchGateway.searchPage(criteria, null);
        PostSearchPage second = searchGateway.searchPage(
                criteria,
                first.nextCursor()
        );
        DecodedPostSearchCursor beforeLast = cursorCodec.decode(
                second.nextCursor(),
                criteria
        );

        PostSearchPage last = searchGateway.searchPage(
                criteria,
                second.nextCursor()
        );

        assertThat(last.candidates()).extracting(PostSearchCandidate::postId)
                .containsExactly(101L);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(elasticsearchClient.closePointInTime(request -> request
                .id(beforeLast.pitId())).numFreed()).isZero();
    }

    private List<Long> collectRemainingIds(
            PostSearchCriteria criteria,
            String initialCursor
    ) {
        List<Long> ids = new ArrayList<>();
        String cursor = initialCursor;
        while (cursor != null) {
            PostSearchPage page = searchGateway.searchPage(criteria, cursor);
            ids.addAll(page.candidates().stream()
                    .map(PostSearchCandidate::postId)
                    .toList());
            cursor = page.nextCursor();
        }
        return List.copyOf(ids);
    }

    private void assertInvalidReuse(String cursor, PostSearchCriteria criteria) {
        assertThatThrownBy(() -> searchGateway.searchPage(criteria, cursor))
                .isInstanceOf(InvalidPostSearchCursorException.class)
                .hasMessageContaining("does not match");
    }

    private PostSearchCriteria criteria(PostSearchSort sort) {
        return new PostSearchCriteria(
                "pagemarker",
                PostSearchScope.ALL,
                sort,
                2
        );
    }

    private void indexPageDocument(long postId) throws Exception {
        PostSearchDocument document = new PostSearchDocument(
                postId,
                "pagemarker 동일한 제목",
                "동일한 페이지 본문",
                CREATED_AT,
                null
        );
        elasticsearchClient.index(request -> request
                .index(PostSearchIndexNames.WRITE_ALIAS)
                .requireAlias(true)
                .id(document.getDocumentId())
                .document(document));
    }

    private void refresh() throws Exception {
        elasticsearchClient.indices().refresh(request -> request
                .index(PostSearchIndexNames.WRITE_ALIAS));
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
