package kr.woo.community.search.query;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostSearchCursorCodecTest {

    private static final String SECRET =
            "cursor-test-secret-at-least-thirty-two-characters";
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");

    @Test
    void roundTripsAnOpaqueSignedRelevanceCursor() {
        PostSearchCursorCodec codec = codecAt(NOW);
        PostSearchCriteria criteria = criteria(
                "대한민국 개발자 커뮤니티",
                PostSearchScope.ALL,
                PostSearchSort.RELEVANCE
        );
        PostSearchSortValues values = new PostSearchSortValues(3.25, 123L, 9L);

        String cursor = codec.encode("pit-id-123", criteria, values);
        DecodedPostSearchCursor decoded = codec.decode(cursor, criteria);

        assertThat(cursor).contains(".");
        assertThat(cursor).doesNotContain("대한민국", "pit-id-123");
        assertThat(decoded.pitId()).isEqualTo("pit-id-123");
        assertThat(decoded.sortValues()).isEqualTo(values);
    }

    @Test
    void rejectsPayloadOrSignatureTampering() {
        PostSearchCursorCodec codec = codecAt(NOW);
        PostSearchCriteria criteria = criteria(
                "검색어",
                PostSearchScope.TITLE,
                PostSearchSort.TIME
        );
        String cursor = codec.encode(
                "pit-id",
                criteria,
                new PostSearchSortValues(null, 10L, 1L)
        );
        char replacement = cursor.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = replacement + cursor.substring(1);

        assertThatThrownBy(() -> codec.decode(tampered, criteria))
                .isInstanceOf(InvalidPostSearchCursorException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void rejectsReuseWithDifferentKeywordScopeOrSort() {
        PostSearchCursorCodec codec = codecAt(NOW);
        PostSearchCriteria original = criteria(
                "원본 검색어",
                PostSearchScope.ALL,
                PostSearchSort.TIME
        );
        String cursor = codec.encode(
                "pit-id",
                original,
                new PostSearchSortValues(null, 10L, 1L)
        );

        assertInvalidReuse(codec, cursor, criteria(
                "다른 검색어",
                PostSearchScope.ALL,
                PostSearchSort.TIME
        ));
        assertInvalidReuse(codec, cursor, criteria(
                "원본 검색어",
                PostSearchScope.TITLE,
                PostSearchSort.TIME
        ));
        assertInvalidReuse(codec, cursor, criteria(
                "원본 검색어",
                PostSearchScope.ALL,
                PostSearchSort.RELEVANCE
        ));
    }

    @Test
    void rejectsAnExpiredCursor() {
        PostSearchCriteria criteria = criteria(
                "검색어",
                PostSearchScope.ALL,
                PostSearchSort.TIME
        );
        String cursor = codecAt(NOW).encode(
                "pit-id",
                criteria,
                new PostSearchSortValues(null, 10L, 1L)
        );
        PostSearchCursorCodec expiredCodec = codecAt(NOW.plusSeconds(61));

        assertThatThrownBy(() -> expiredCodec.decode(cursor, criteria))
                .isInstanceOf(ExpiredPostSearchCursorException.class);
    }

    @Test
    void rejectsAnOversizedCursorBeforeDecoding() {
        PostSearchCursorCodec codec = codecAt(NOW);

        assertThatThrownBy(() -> codec.decode(
                "a".repeat(8_193),
                criteria("검색어", PostSearchScope.ALL, PostSearchSort.TIME)
        )).isInstanceOf(InvalidPostSearchCursorException.class)
                .hasMessageContaining("too long");
    }

    private void assertInvalidReuse(
            PostSearchCursorCodec codec,
            String cursor,
            PostSearchCriteria criteria
    ) {
        assertThatThrownBy(() -> codec.decode(cursor, criteria))
                .isInstanceOf(InvalidPostSearchCursorException.class)
                .hasMessageContaining("does not match");
    }

    private PostSearchCursorCodec codecAt(Instant instant) {
        return new PostSearchCursorCodec(
                JsonMapper.builder().build(),
                SECRET,
                Duration.ofMinutes(1),
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private PostSearchCriteria criteria(
            String keyword,
            PostSearchScope scope,
            PostSearchSort sort
    ) {
        return new PostSearchCriteria(keyword, scope, sort, 2);
    }
}
