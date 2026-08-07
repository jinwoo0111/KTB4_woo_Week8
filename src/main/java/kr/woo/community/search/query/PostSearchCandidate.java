package kr.woo.community.search.query;

import java.util.Objects;

public record PostSearchCandidate(
        long postId,
        Double score,
        PostSearchSortValues sortValues
) {

    public PostSearchCandidate {
        if (postId <= 0) {
            throw new IllegalArgumentException("postId must be positive");
        }
        if (score != null && (!Double.isFinite(score) || score < 0)) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
        Objects.requireNonNull(sortValues, "sortValues");
        if (sortValues.postId() != postId) {
            throw new IllegalArgumentException("postId and postId sort value must match");
        }
    }
}
