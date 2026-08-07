package kr.woo.community.search.query;

public record PostSearchSortValues(
        Double relevanceScore,
        long postId,
        Long pitShardDoc
) {

    public PostSearchSortValues(Double relevanceScore, long postId) {
        this(relevanceScore, postId, null);
    }

    public PostSearchSortValues {
        if (postId <= 0) {
            throw new IllegalArgumentException("postId sort value must be positive");
        }
        if (relevanceScore != null
                && (!Double.isFinite(relevanceScore) || relevanceScore < 0)) {
            throw new IllegalArgumentException(
                    "relevanceScore sort value must be finite and non-negative"
            );
        }
        if (pitShardDoc != null && pitShardDoc < 0) {
            throw new IllegalArgumentException("pitShardDoc must be non-negative");
        }
    }
}
