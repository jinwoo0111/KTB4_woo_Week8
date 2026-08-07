package kr.woo.community.search.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostSearchCriteriaTest {

    @Test
    void stripsTheKeywordAndKeepsTheSearchContract() {
        PostSearchCriteria criteria = new PostSearchCriteria(
                "  대한민국 개발자 커뮤니티  ",
                PostSearchScope.ALL,
                PostSearchSort.RELEVANCE,
                10
        );

        assertThat(criteria.keyword()).isEqualTo("대한민국 개발자 커뮤니티");
        assertThat(criteria.scope()).isEqualTo(PostSearchScope.ALL);
        assertThat(criteria.sort()).isEqualTo(PostSearchSort.RELEVANCE);
        assertThat(criteria.limit()).isEqualTo(10);
    }

    @Test
    void rejectsValuesOutsideTheInternalSearchContract() {
        assertThatThrownBy(() -> new PostSearchCriteria(
                " ",
                PostSearchScope.ALL,
                PostSearchSort.TIME,
                10
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PostSearchCriteria(
                "검색어",
                null,
                PostSearchSort.TIME,
                10
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new PostSearchCriteria(
                "검색어",
                PostSearchScope.ALL,
                null,
                10
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new PostSearchCriteria(
                "검색어",
                PostSearchScope.ALL,
                PostSearchSort.TIME,
                101
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
