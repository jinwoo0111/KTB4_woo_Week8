package kr.woo.community.benchmark;

public record BenchmarkPostData(
        long sequence,
        long activeSequence,
        int authorIndex,
        String title,
        String content,
        boolean deleted
) {
}
