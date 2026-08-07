package kr.woo.community.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import kr.woo.community.search.document.PostSearchDocument;
import kr.woo.community.search.index.PostSearchIndexInitializer;
import kr.woo.community.search.index.PostSearchIndexNames;
import kr.woo.community.search.query.ElasticsearchPostSearchGateway;
import kr.woo.community.search.query.PostSearchCandidate;
import kr.woo.community.search.query.PostSearchCriteria;
import kr.woo.community.search.query.PostSearchExecutionException;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ElasticsearchTestcontainersConfiguration.class)
@ActiveProfiles("test")
class ElasticsearchPostSearchGatewayIntegrationTest {

    private static final LocalDateTime CREATED_AT =
            LocalDateTime.of(2026, 8, 6, 10, 0);

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ElasticsearchPostSearchGateway searchGateway;

    @BeforeEach
    void setUp() throws Exception {
        deleteInitialIndexIfPresent();
        new PostSearchIndexInitializer(elasticsearchClient).initialize();
        indexFixtures();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteInitialIndexIfPresent();
    }

    @Test
    void appliesAllTitleAndContentScopesToTheExpectedFields() {
        List<PostSearchCandidate> all = search(
                "scopeuniquemarker",
                PostSearchScope.ALL,
                PostSearchSort.TIME
        );
        List<PostSearchCandidate> title = search(
                "scopeuniquemarker",
                PostSearchScope.TITLE,
                PostSearchSort.TIME
        );
        List<PostSearchCandidate> content = search(
                "scopeuniquemarker",
                PostSearchScope.CONTENT,
                PostSearchSort.TIME
        );

        assertThat(all).extracting(PostSearchCandidate::postId)
                .containsExactly(2L, 1L);
        assertThat(title).extracting(PostSearchCandidate::postId)
                .containsExactly(1L);
        assertThat(content).extracting(PostSearchCandidate::postId)
                .containsExactly(2L);
    }

    @Test
    void searchesKoreanWhitespaceAndLowercasesEnglishWithNori() {
        List<PostSearchCandidate> korean = search(
                "대한민국 개발자 커뮤니티",
                PostSearchScope.ALL,
                PostSearchSort.TIME
        );
        List<PostSearchCandidate> english = search(
                "SPRING",
                PostSearchScope.ALL,
                PostSearchSort.TIME
        );

        assertThat(korean).extracting(PostSearchCandidate::postId)
                .contains(1L, 2L);
        assertThat(english).extracting(PostSearchCandidate::postId)
                .containsExactly(3L);
    }

    @Test
    void distinguishesTimeAndRelevanceOrderingAndPreservesSortValues() {
        List<PostSearchCandidate> time = search(
                "rankmarker specialterm",
                PostSearchScope.ALL,
                PostSearchSort.TIME
        );
        List<PostSearchCandidate> relevance = search(
                "rankmarker specialterm",
                PostSearchScope.ALL,
                PostSearchSort.RELEVANCE
        );

        assertThat(time).extracting(PostSearchCandidate::postId)
                .containsExactly(30L, 10L);
        assertThat(time).allSatisfy(candidate -> {
            assertThat(candidate.sortValues().relevanceScore()).isNull();
            assertThat(candidate.sortValues().postId()).isEqualTo(candidate.postId());
        });

        assertThat(relevance).extracting(PostSearchCandidate::postId)
                .containsExactly(10L, 30L);
        assertThat(relevance).allSatisfy(candidate -> {
            assertThat(candidate.score()).isNotNull();
            assertThat(candidate.sortValues().relevanceScore())
                    .isEqualTo(candidate.score());
            assertThat(candidate.sortValues().postId()).isEqualTo(candidate.postId());
        });
    }

    @Test
    void breaksEqualRelevanceScoresByPostIdDescending() {
        List<PostSearchCandidate> candidates = search(
                "tiemarker",
                PostSearchScope.ALL,
                PostSearchSort.RELEVANCE
        );

        assertThat(candidates).extracting(PostSearchCandidate::postId)
                .containsExactly(50L, 40L);
        assertThat(candidates.get(0).score()).isEqualTo(candidates.get(1).score());
    }

    @Test
    void failsWhenTheReadAliasIsMissingEvenThoughThePhysicalIndexExists() throws Exception {
        elasticsearchClient.indices().deleteAlias(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
                .name(PostSearchIndexNames.READ_ALIAS));

        assertThatThrownBy(() -> search(
                "대한민국 개발자 커뮤니티",
                PostSearchScope.ALL,
                PostSearchSort.TIME
        )).isInstanceOf(PostSearchExecutionException.class);

        assertThat(elasticsearchClient.indices().exists(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)).value()).isTrue();
    }

    private List<PostSearchCandidate> search(
            String keyword,
            PostSearchScope scope,
            PostSearchSort sort
    ) {
        return searchGateway.search(new PostSearchCriteria(keyword, scope, sort, 20));
    }

    private void indexFixtures() throws Exception {
        index(new PostSearchDocument(
                1L,
                "scopeuniquemarker 대한민국 개발자 커뮤니티",
                "제목 범위와 한국어 검색 fixture",
                CREATED_AT,
                null
        ));
        index(new PostSearchDocument(
                2L,
                "본문 범위 fixture",
                "scopeuniquemarker 대한민국 개발자 커뮤니티",
                CREATED_AT,
                null
        ));
        index(new PostSearchDocument(
                3L,
                "영문 검색 fixture",
                "Spring 기반 검색",
                CREATED_AT,
                null
        ));
        index(new PostSearchDocument(
                10L,
                "rankmarker specialterm",
                "관련도 정렬 fixture",
                CREATED_AT,
                null
        ));
        index(new PostSearchDocument(
                30L,
                "rankmarker",
                "관련도 정렬 fixture",
                CREATED_AT,
                null
        ));
        index(new PostSearchDocument(
                40L,
                "tiemarker 동일한 제목",
                "동일한 본문",
                CREATED_AT,
                null
        ));
        index(new PostSearchDocument(
                50L,
                "tiemarker 동일한 제목",
                "동일한 본문",
                CREATED_AT,
                null
        ));
        elasticsearchClient.indices().refresh(request -> request
                .index(PostSearchIndexNames.WRITE_ALIAS));
    }

    private void index(PostSearchDocument document) throws Exception {
        elasticsearchClient.index(request -> request
                .index(PostSearchIndexNames.WRITE_ALIAS)
                .requireAlias(true)
                .id(document.getDocumentId())
                .document(document));
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
