package kr.woo.community.search.query;

import java.util.Objects;

public record PostSearchCriteria(
        String keyword,
        PostSearchScope scope,
        PostSearchSort sort,
        int limit
) {

    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int MAX_LIMIT = 100;

    public PostSearchCriteria {
        Objects.requireNonNull(keyword, "keyword");
        keyword = keyword.strip();
        if (keyword.length() < MIN_KEYWORD_LENGTH
                || keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "keyword length must be between "
                            + MIN_KEYWORD_LENGTH + " and " + MAX_KEYWORD_LENGTH
            );
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sort, "sort");
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
