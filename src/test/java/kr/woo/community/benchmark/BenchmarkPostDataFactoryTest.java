package kr.woo.community.benchmark;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkPostDataFactoryTest {
    private static final long SEED = 20260802L;

    @Test
    void createsSameDataForSameSeedAndSequence() {
        BenchmarkPostDataFactory firstFactory = new BenchmarkPostDataFactory(SEED, 1_000, 100);
        BenchmarkPostDataFactory secondFactory = new BenchmarkPostDataFactory(SEED, 1_000, 100);

        BenchmarkPostData first = firstFactory.create(321);
        BenchmarkPostData second = secondFactory.create(321);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void createsExpectedLengthDistributionForOneHundredPosts() {
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(SEED, 1_000, 100);
        int shortCount = 0;
        int mediumCount = 0;
        int longCount = 0;
        int veryLongCount = 0;

        for (int sequence = 1; sequence <= 100; sequence++) {
            int contentLength = factory.create(sequence).content().length();

            if (contentLength <= 799) {
                shortCount++;
            } else if (contentLength <= 1_999) {
                mediumCount++;
            } else if (contentLength <= 7_999) {
                longCount++;
            } else {
                veryLongCount++;
            }
        }

        assertThat(shortCount).isEqualTo(60);
        assertThat(mediumCount).isEqualTo(30);
        assertThat(longCount).isEqualTo(9);
        assertThat(veryLongCount).isEqualTo(1);
    }

    @Test
    void createsExpectedMarkerAndDeletionCounts() {
        int postCount = 10_000;
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(SEED, postCount, 100);
        List<BenchmarkPostData> data = createAll(factory, postCount);

        assertThat(data.stream().filter(BenchmarkPostData::deleted)).hasSize(500);
        assertThat(factory.getActivePostCount()).isEqualTo(9_500);
        assertThat(countContentMarker(data, BenchmarkSearchMarkers.COMMON)).isEqualTo(950);
        assertThat(countContentMarker(data, BenchmarkSearchMarkers.MEDIUM)).isEqualTo(95);
        assertThat(countContentMarker(data, BenchmarkSearchMarkers.RARE)).isEqualTo(9);
        assertThat(countContentMarker(data, BenchmarkSearchMarkers.FIXED)).isEqualTo(10);
        assertThat(countContentMarker(data, BenchmarkSearchMarkers.SCOPE)).isEqualTo(95);
        assertThat(countContentMarker(data, BenchmarkSearchMarkers.NEVER)).isZero();
    }

    @Test
    void placesScopeMarkerInTitleAndContentOfSamePosts() {
        int postCount = 10_000;
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(SEED, postCount, 100);
        List<BenchmarkPostData> data = createAll(factory, postCount);

        List<Long> titleSequences = data.stream()
                .filter(post -> post.title().contains(BenchmarkSearchMarkers.SCOPE))
                .map(BenchmarkPostData::sequence)
                .toList();
        List<Long> contentSequences = data.stream()
                .filter(post -> post.content().contains(BenchmarkSearchMarkers.SCOPE))
                .map(BenchmarkPostData::sequence)
                .toList();

        assertThat(titleSequences).containsExactlyElementsOf(contentSequences);
    }

    @Test
    void doesNotPlaceMarkersInDeletedPosts() {
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(SEED, 1_000, 100);

        for (int sequence = 20; sequence <= 1_000; sequence += 20) {
            BenchmarkPostData data = factory.create(sequence);

            assertThat(data.deleted()).isTrue();
            assertThat(data.activeSequence()).isZero();
            assertThat(data.title()).doesNotContain(
                    BenchmarkSearchMarkers.COMMON,
                    BenchmarkSearchMarkers.MEDIUM,
                    BenchmarkSearchMarkers.RARE,
                    BenchmarkSearchMarkers.FIXED,
                    BenchmarkSearchMarkers.SCOPE,
                    BenchmarkSearchMarkers.NEVER
            );
            assertThat(data.content()).doesNotContain(
                    BenchmarkSearchMarkers.COMMON,
                    BenchmarkSearchMarkers.MEDIUM,
                    BenchmarkSearchMarkers.RARE,
                    BenchmarkSearchMarkers.FIXED,
                    BenchmarkSearchMarkers.SCOPE,
                    BenchmarkSearchMarkers.NEVER
            );
        }
    }

    @Test
    void distributesAuthorsInRoundRobinOrder() {
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(SEED, 1_000, 100);

        assertThat(factory.create(1).authorIndex()).isZero();
        assertThat(factory.create(100).authorIndex()).isEqualTo(99);
        assertThat(factory.create(101).authorIndex()).isZero();
    }

    @Test
    void rejectsSequenceOutsideConfiguredPostCount() {
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(SEED, 1_000, 100);

        assertThatThrownBy(() -> factory.create(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(1_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<BenchmarkPostData> createAll(BenchmarkPostDataFactory factory, int postCount) {
        List<BenchmarkPostData> data = new ArrayList<>(postCount);
        for (int sequence = 1; sequence <= postCount; sequence++) {
            data.add(factory.create(sequence));
        }
        return data;
    }

    private long countContentMarker(List<BenchmarkPostData> data, String marker) {
        return data.stream()
                .filter(post -> post.content().contains(marker))
                .count();
    }
}
