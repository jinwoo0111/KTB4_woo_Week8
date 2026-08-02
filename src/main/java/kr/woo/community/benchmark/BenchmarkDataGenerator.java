package kr.woo.community.benchmark;

import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
@Profile("benchmark")
@ConditionalOnProperty(
        prefix = "app.benchmark.generator",
        name = "enabled",
        havingValue = "true"
)
public class BenchmarkDataGenerator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkDataGenerator.class);
    private static final String BENCHMARK_PATH_MARKER = "benchmark-data";

    private final BenchmarkGeneratorProperties properties;
    private final DataSource dataSource;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final BenchmarkBatchWriter batchWriter;

    public BenchmarkDataGenerator(
            BenchmarkGeneratorProperties properties,
            DataSource dataSource,
            PostRepository postRepository,
            UserRepository userRepository,
            BenchmarkBatchWriter batchWriter
    ) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.batchWriter = batchWriter;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        String databaseUrl = getDatabaseUrl();

        validateBenchmarkDatabase(databaseUrl);
        validateEmptyDatabase();

        log.info("Benchmark generator configuration validated");
        log.info("Target database: {}", databaseUrl);
        log.info(
                "postCount={}, authorCount={}, persistenceBatchSize={}, seed={}",
                properties.postCount(),
                properties.authorCount(),
                properties.persistenceBatchSize(),
                properties.seed()
        );

        long startedAt = System.nanoTime();
        generateData();
        validateGeneratedCounts();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        log.info(
                "Benchmark data generation completed: posts={}, authors={}, elapsedMs={}",
                properties.postCount(),
                properties.authorCount(),
                elapsedMillis
        );
    }

    private String getDatabaseUrl() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }

    private void validateBenchmarkDatabase(String databaseUrl) {
        if (databaseUrl == null || !databaseUrl.contains(BENCHMARK_PATH_MARKER)) {
            throw new IllegalStateException(
                    "Benchmark data generation is only allowed for a database under benchmark-data"
            );
        }
    }

    private void validateEmptyDatabase() {
        long postCount = postRepository.count();
        long userCount = userRepository.count();

        if (postCount > 0 || userCount > 0) {
            throw new IllegalStateException(
                    "Benchmark database must be empty before data generation: posts="
                            + postCount
                            + ", users="
                            + userCount
            );
        }
    }

    private void generateData() {
        BenchmarkPostDataFactory dataFactory = new BenchmarkPostDataFactory(
                properties.seed(),
                properties.postCount(),
                properties.authorCount()
        );
        var authorIds = batchWriter.saveAuthors(properties.authorCount());
        int batchSize = properties.persistenceBatchSize();

        for (long startSequence = 1; startSequence <= properties.postCount(); startSequence += batchSize) {
            long endSequence = Math.min(
                    startSequence + batchSize - 1,
                    properties.postCount()
            );

            batchWriter.savePostBatch(
                    dataFactory,
                    startSequence,
                    endSequence,
                    authorIds
            );

            logProgress(endSequence);
        }
    }

    private void logProgress(long generatedPostCount) {
        int progressPercent = (int) (generatedPostCount * 100 / properties.postCount());
        log.info(
                "Benchmark post generation progress: {}/{} ({}%)",
                generatedPostCount,
                properties.postCount(),
                progressPercent
        );
    }

    private void validateGeneratedCounts() {
        long actualPostCount = postRepository.count();
        long actualAuthorCount = userRepository.count();

        if (actualPostCount != properties.postCount()
                || actualAuthorCount != properties.authorCount()) {
            throw new IllegalStateException(
                    "Generated row count mismatch: expected posts="
                            + properties.postCount()
                            + ", actual posts="
                            + actualPostCount
                            + ", expected authors="
                            + properties.authorCount()
                            + ", actual authors="
                            + actualAuthorCount
            );
        }
    }
}
