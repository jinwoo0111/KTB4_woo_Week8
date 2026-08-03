package kr.woo.community.benchmark;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "app.benchmark.generator")
public record BenchmarkGeneratorProperties(
        boolean enabled,
        @Min(1) int postCount,
        @Min(1) int authorCount,
        @Min(1) int persistenceBatchSize,
        long seed
) {
    private static final Set<Integer> SUPPORTED_POST_COUNTS = Set.of(
            1_000,
            10_000,
            100_000,
            500_000
    );

    @AssertTrue(message = "post-count must be one of 1000, 10000, 100000, or 500000")
    public boolean isSupportedPostCount() {
        return SUPPORTED_POST_COUNTS.contains(postCount);
    }

    @AssertTrue(message = "author-count must not exceed post-count")
    public boolean isAuthorCountValid() {
        return authorCount <= postCount;
    }

    @AssertTrue(message = "persistence-batch-size must not exceed post-count")
    public boolean isPersistenceBatchSizeValid() {
        return persistenceBatchSize <= postCount;
    }
}
