package kr.woo.community.search.query;

import java.util.List;

public record PostSearchPage(
        List<PostSearchCandidate> candidates,
        boolean hasNext,
        String nextCursor
) {

    public PostSearchPage {
        candidates = List.copyOf(candidates);
        if (hasNext != (nextCursor != null)) {
            throw new IllegalArgumentException(
                    "hasNext and nextCursor presence must match"
            );
        }
    }
}
