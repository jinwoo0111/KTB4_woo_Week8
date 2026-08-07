package kr.woo.community.search.query;

import java.util.Objects;

public record DecodedPostSearchCursor(
        String pitId,
        PostSearchSortValues sortValues
) {

    public DecodedPostSearchCursor {
        if (pitId == null || pitId.isBlank()) {
            throw new IllegalArgumentException("pitId must not be blank");
        }
        Objects.requireNonNull(sortValues, "sortValues");
        if (sortValues.pitShardDoc() == null) {
            throw new IllegalArgumentException("PIT cursor requires pitShardDoc");
        }
    }
}
