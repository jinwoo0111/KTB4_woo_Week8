package kr.woo.community.benchmark;

import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class BenchmarkDataGeneratorTest {
    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData databaseMetaData;

    @Mock
    private PostRepository postRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BenchmarkBatchWriter batchWriter;

    private BenchmarkGeneratorProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        properties = new BenchmarkGeneratorProperties(
                true,
                1_000,
                100,
                1_000,
                20260802L
        );

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
    }

    @Test
    void acceptsEmptyBenchmarkDatabase() throws Exception {
        stubPostgreSqlBenchmarkDatabase();
        when(postRepository.count()).thenReturn(0L, 1_000L);
        when(userRepository.count()).thenReturn(0L, 100L);
        when(batchWriter.saveAuthors(100)).thenReturn(authorIds(100));

        BenchmarkDataGenerator generator = createGenerator();

        assertThatCode(() -> generator.run(null))
                .doesNotThrowAnyException();

        verify(batchWriter).saveAuthors(100);
        verify(batchWriter).savePostBatch(
                any(BenchmarkPostDataFactory.class),
                eq(1L),
                eq(1_000L),
                eq(authorIds(100))
        );
    }

    @Test
    void rejectsDatabaseOtherThanPostgreSql() throws Exception {
        when(databaseMetaData.getDatabaseProductName()).thenReturn("H2");

        BenchmarkDataGenerator generator = createGenerator();

        assertThatThrownBy(() -> generator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL")
                .hasMessageContaining("actual product=H2");
    }

    @Test
    void rejectsPostgreSqlDatabaseOtherThanCommunityBenchmark() throws Exception {
        when(databaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getCatalog()).thenReturn("community");

        BenchmarkDataGenerator generator = createGenerator();

        assertThatThrownBy(() -> generator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("community_benchmark")
                .hasMessageContaining("actual database=community");
    }

    @Test
    void rejectsNonEmptyBenchmarkDatabase() throws Exception {
        stubPostgreSqlBenchmarkDatabase();
        when(postRepository.count()).thenReturn(1L);
        when(userRepository.count()).thenReturn(0L);

        BenchmarkDataGenerator generator = createGenerator();

        assertThatThrownBy(() -> generator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("posts=1")
                .hasMessageContaining("users=0");
    }

    private BenchmarkDataGenerator createGenerator() {
        return new BenchmarkDataGenerator(
                properties,
                dataSource,
                postRepository,
                userRepository,
                batchWriter
        );
    }

    private void stubPostgreSqlBenchmarkDatabase() throws Exception {
        when(databaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getCatalog()).thenReturn("community_benchmark");
        when(databaseMetaData.getURL())
                .thenReturn("jdbc:postgresql://127.0.0.1:5433/community_benchmark");
    }

    private List<Long> authorIds(int authorCount) {
        return LongStream.rangeClosed(1, authorCount)
                .boxed()
                .toList();
    }
}
