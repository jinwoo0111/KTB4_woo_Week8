package kr.woo.community.benchmark;

import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benchmark-data-generation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.benchmark.generator.enabled=true",
        "app.benchmark.generator.post-count=1000",
        "app.benchmark.generator.author-count=100",
        "app.benchmark.generator.persistence-batch-size=1000",
        "app.benchmark.generator.seed=20260802"
})
@ActiveProfiles({"benchmark", "test"})
class BenchmarkDataGenerationIntegrationTest {
    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void generatesExpectedRowsAndSearchMarkerDistribution() {
        assertThat(userRepository.count()).isEqualTo(100);
        assertThat(postRepository.count()).isEqualTo(1_000);
        assertThat(countDeletedPosts()).isEqualTo(50);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.COMMON)).isEqualTo(95);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.MEDIUM)).isEqualTo(9);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.RARE)).isZero();
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.FIXED)).isEqualTo(10);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.SCOPE)).isEqualTo(9);
        assertThat(countActiveContentMarker(BenchmarkSearchMarkers.NEVER)).isZero();
        assertThat(countActiveTitleMarker(BenchmarkSearchMarkers.SCOPE)).isEqualTo(9);
    }

    private long countDeletedPosts() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE deleted_at IS NOT NULL",
                Long.class
        );
    }

    private long countActiveContentMarker(String marker) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts "
                        + "WHERE deleted_at IS NULL AND content LIKE ?",
                Long.class,
                "%" + marker + "%"
        );
    }

    private long countActiveTitleMarker(String marker) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts "
                        + "WHERE deleted_at IS NULL AND title LIKE ?",
                Long.class,
                "%" + marker + "%"
        );
    }
}
