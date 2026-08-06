package kr.woo.community.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import kr.woo.community.search.document.PostSearchDocument;
import kr.woo.community.search.index.PostSearchIndexDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ElasticsearchTestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostSearchIndexDefinitionIntegrationTest {

    private static final String TEST_INDEX = "community-posts-mapping-test";

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @BeforeAll
    void createIndex() throws Exception {
        elasticsearchClient.indices().create(
                PostSearchIndexDefinition.createIndexRequest(TEST_INDEX)
        );
    }

    @AfterAll
    void deleteIndex() throws Exception {
        elasticsearchClient.indices().delete(request -> request.index(TEST_INDEX));
    }

    @Test
    void createsStrictPostSearchMapping() throws Exception {
        TypeMapping mapping = elasticsearchClient.indices()
                .getMapping(request -> request.index(TEST_INDEX))
                .get(TEST_INDEX)
                .mappings();

        assertThat(mapping.dynamic()).isEqualTo(DynamicMapping.Strict);
        assertThat(mapping.source() == null || mapping.source().enabled() == null
                || mapping.source().enabled()).isTrue();
        assertThat(mapping.properties()).containsOnlyKeys(
                "post_id",
                "title",
                "content",
                "created_at",
                "updated_at"
        );

        Property postId = mapping.properties().get("post_id");
        assertThat(postId.isLong()).isTrue();

        assertTextMapping(mapping.properties().get("title"));
        assertTextMapping(mapping.properties().get("content"));
        assertAuditDateMapping(mapping.properties().get("created_at"));
        assertAuditDateMapping(mapping.properties().get("updated_at"));
    }

    @Test
    void analyzesKoreanCompoundsAndEnglishWithTheConfiguredAnalyzers() throws Exception {
        List<String> indexTokens = analyze(
                PostSearchIndexDefinition.INDEX_ANALYZER,
                "대한민국 개발자 커뮤니티 Spring SPRING"
        );
        List<String> searchTokens = analyze(
                PostSearchIndexDefinition.SEARCH_ANALYZER,
                "대한민국 개발자 커뮤니티 Spring SPRING"
        );

        assertThat(indexTokens).contains(
                "대한민국",
                "대한",
                "민국",
                "개발자",
                "개발",
                "자",
                "커뮤니티",
                "spring"
        );
        assertThat(searchTokens).isEqualTo(indexTokens);
        assertThat(indexTokens).doesNotContain("Spring", "SPRING");
    }

    @Test
    void rejectsFieldsOutsideTheStrictDocumentMapping() {
        Map<String, Object> invalidDocument = new LinkedHashMap<>();
        invalidDocument.put("post_id", 1L);
        invalidDocument.put("title", "검색 제목");
        invalidDocument.put("content", "검색 본문");
        invalidDocument.put("created_at", "2026-08-06T15:12:44.011");
        invalidDocument.put("author_profile_image", "/profile.png");

        assertThatThrownBy(() -> elasticsearchClient.index(request -> request
                .index(TEST_INDEX)
                .id("1")
                .document(invalidDocument)))
                .isInstanceOf(ElasticsearchException.class);
    }

    @Test
    void indexesTheExactSearchDocumentProjection() throws Exception {
        PostSearchDocument document = new PostSearchDocument(
                2L,
                "대한민국 개발자 커뮤니티",
                "한국어 검색 본문",
                LocalDateTime.of(2026, 8, 6, 15, 12, 44, 11_000_000),
                null
        );

        var response = elasticsearchClient.index(request -> request
                .index(TEST_INDEX)
                .id(document.getDocumentId())
                .document(document));

        assertThat(response.id()).isEqualTo("2");
        assertThat(response.index()).isEqualTo(TEST_INDEX);
    }

    private List<String> analyze(String analyzer, String text) throws Exception {
        AnalyzeResponse response = elasticsearchClient.indices().analyze(request -> request
                .index(TEST_INDEX)
                .analyzer(analyzer)
                .text(text));

        return response.tokens().stream()
                .map(token -> token.token())
                .toList();
    }

    private void assertTextMapping(Property property) {
        assertThat(property.isText()).isTrue();
        assertThat(property.text().analyzer())
                .isEqualTo(PostSearchIndexDefinition.INDEX_ANALYZER);
        assertThat(property.text().searchAnalyzer())
                .isEqualTo(PostSearchIndexDefinition.SEARCH_ANALYZER);
    }

    private void assertAuditDateMapping(Property property) {
        assertThat(property.isDate()).isTrue();
        assertThat(property.date().format()).isEqualTo("strict_date_optional_time_nanos");
        assertThat(property.date().index()).isFalse();
        assertThat(property.date().docValues()).isFalse();
    }
}
