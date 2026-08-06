package kr.woo.community.search.document;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostSearchDocumentTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void serializesOnlyTheSearchProjectionAndUsesPostIdAsDocumentId() throws Exception {
        PostSearchDocument document = new PostSearchDocument(
                123L,
                "대한민국 개발자 커뮤니티",
                "한국어 검색을 위한 본문입니다.",
                LocalDateTime.of(2026, 8, 6, 15, 12, 44, 11_000_000),
                null
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(document));

        assertThat(document.getDocumentId()).isEqualTo("123");
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "post_id",
                "title",
                "content",
                "created_at"
        );
        assertThat(json.get("post_id").asLong()).isEqualTo(123L);
        assertThat(json.get("created_at").asString())
                .isEqualTo("2026-08-06T15:12:44.011");
        assertThat(json.has("updated_at")).isFalse();
    }

    @Test
    void serializesUpdatedAtWhenItExists() throws Exception {
        PostSearchDocument document = new PostSearchDocument(
                1L,
                "검색 제목",
                "검색 본문",
                LocalDateTime.of(2026, 8, 6, 10, 0),
                LocalDateTime.of(2026, 8, 6, 11, 30, 15, 123_456_000)
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(document));

        assertThat(json.get("updated_at").asString())
                .isEqualTo("2026-08-06T11:30:15.123456");
    }

    @Test
    void rejectsValuesOutsideThePostgreSqlDocumentContract() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 6, 10, 0);

        assertThatThrownBy(() -> new PostSearchDocument(
                0L,
                "검색 제목",
                "검색 본문",
                createdAt,
                null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PostSearchDocument(
                1L,
                " ",
                "검색 본문",
                createdAt,
                null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PostSearchDocument(
                1L,
                "검색 제목",
                "가".repeat(32_001),
                createdAt,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
