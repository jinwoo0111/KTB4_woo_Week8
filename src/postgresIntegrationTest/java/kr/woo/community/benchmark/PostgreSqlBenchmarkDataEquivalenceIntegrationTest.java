package kr.woo.community.benchmark;

import kr.woo.community.PostgreSqlTestcontainersConfiguration;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "BENCHMARK_POSTGRES_PASSWORD=unused-because-service-connection-overrides-it",
        "app.benchmark.generator.enabled=true",
        "app.benchmark.generator.post-count=1000",
        "app.benchmark.generator.author-count=100",
        "app.benchmark.generator.persistence-batch-size=1000",
        "app.benchmark.generator.seed=20260802"
})
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles({"benchmark", "postgres-integration-test"})
class PostgreSqlBenchmarkDataEquivalenceIntegrationTest {
    private static final long SEED = 20260802L;
    private static final int POST_COUNT = 1_000;
    private static final int AUTHOR_COUNT = 100;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void generatesSameLogicalRowsAsDeterministicFactory() {
        BenchmarkPostDataFactory factory = new BenchmarkPostDataFactory(
                SEED,
                POST_COUNT,
                AUTHOR_COUNT
        );
        List<StoredBenchmarkPost> storedPosts = jdbcTemplate.query(
                "SELECT p.title, p.content, p.deleted_at IS NOT NULL, u.email "
                        + "FROM posts p JOIN users u ON u.user_id = p.user_id "
                        + "ORDER BY p.post_id",
                (resultSet, rowNumber) -> new StoredBenchmarkPost(
                        resultSet.getString("title"),
                        resultSet.getString("content"),
                        resultSet.getBoolean(3),
                        resultSet.getString("email")
                )
        );

        assertThat(storedPosts).hasSize(POST_COUNT);

        for (int index = 0; index < storedPosts.size(); index++) {
            long sequence = index + 1L;
            BenchmarkPostData expected = factory.create(sequence);
            StoredBenchmarkPost actual = storedPosts.get(index);

            assertThat(actual.title()).isEqualTo(expected.title());
            assertThat(actual.content()).isEqualTo(expected.content());
            assertThat(actual.deleted()).isEqualTo(expected.deleted());
            assertThat(actual.authorEmail())
                    .isEqualTo("benchmark-" + expected.authorIndex() + "@example.invalid");
        }
    }

    @Test
    void preservesRowCountsAuthorDistributionAndDefaultValues() {
        assertThat(userRepository.count()).isEqualTo(AUTHOR_COUNT);
        assertThat(postRepository.count()).isEqualTo(POST_COUNT);
        assertThat(count("SELECT COUNT(*) FROM posts WHERE deleted_at IS NULL")).isEqualTo(950);
        assertThat(count("SELECT COUNT(*) FROM posts WHERE deleted_at IS NOT NULL")).isEqualTo(50);
        assertThat(count(
                "SELECT COUNT(*) FROM ("
                        + "SELECT user_id FROM posts GROUP BY user_id HAVING COUNT(*) <> 10"
                        + ") unexpected_author_distribution"
        )).isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM posts WHERE content_image IS NOT NULL "
                        + "OR like_count <> 0 OR comment_count <> 0 OR view_count <> 0"
        )).isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM users WHERE profile_image IS NOT NULL"
        )).isZero();
    }

    @Test
    void preservesSearchMarkerDistribution() {
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.COMMON)).isEqualTo(95);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.MEDIUM)).isEqualTo(9);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.RARE)).isZero();
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.FIXED)).isEqualTo(10);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.SCOPE)).isEqualTo(9);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.NEVER)).isZero();
        assertThat(countActiveTitleMarker(BenchmarkSearchMarkers.SCOPE)).isEqualTo(9);
        assertThat(countDeletedPostsContainingAnyMarker()).isZero();
    }

    @Test
    void preservesContentLengthDistribution() {
        assertThat(countContentLengthBetween(300, 799)).isEqualTo(600);
        assertThat(countContentLengthBetween(800, 1_999)).isEqualTo(300);
        assertThat(countContentLengthBetween(2_000, 7_999)).isEqualTo(90);
        assertThat(countContentLengthBetween(8_000, 15_999)).isZero();
        assertThat(countContentLengthBetween(16_000, 32_000)).isEqualTo(10);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private long countActiveContentMarker(String marker) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE deleted_at IS NULL AND content LIKE ?",
                Long.class,
                "%" + marker + "%"
        );
    }

    private long countActiveTitleMarker(String marker) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE deleted_at IS NULL AND title LIKE ?",
                Long.class,
                "%" + marker + "%"
        );
    }

    private long countDeletedPostsContainingAnyMarker() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE deleted_at IS NOT NULL AND ("
                        + "title LIKE ? OR content LIKE ? OR "
                        + "title LIKE ? OR content LIKE ? OR "
                        + "title LIKE ? OR content LIKE ? OR "
                        + "title LIKE ? OR content LIKE ? OR "
                        + "title LIKE ? OR content LIKE ? OR "
                        + "title LIKE ? OR content LIKE ?)",
                Long.class,
                markerPattern(BenchmarkSearchMarkers.COMMON), markerPattern(BenchmarkSearchMarkers.COMMON),
                markerPattern(BenchmarkSearchMarkers.MEDIUM), markerPattern(BenchmarkSearchMarkers.MEDIUM),
                markerPattern(BenchmarkSearchMarkers.RARE), markerPattern(BenchmarkSearchMarkers.RARE),
                markerPattern(BenchmarkSearchMarkers.FIXED), markerPattern(BenchmarkSearchMarkers.FIXED),
                markerPattern(BenchmarkSearchMarkers.SCOPE), markerPattern(BenchmarkSearchMarkers.SCOPE),
                markerPattern(BenchmarkSearchMarkers.NEVER), markerPattern(BenchmarkSearchMarkers.NEVER)
        );
    }

    private String markerPattern(String marker) {
        return "%" + marker + "%";
    }

    private long countContentLengthBetween(int minimum, int maximum) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE char_length(content) BETWEEN ? AND ?",
                Long.class,
                minimum,
                maximum
        );
    }

    private record StoredBenchmarkPost(
            String title,
            String content,
            boolean deleted,
            String authorEmail
    ) {
    }
}
