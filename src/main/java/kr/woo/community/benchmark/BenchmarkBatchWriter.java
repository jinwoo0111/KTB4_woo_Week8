package kr.woo.community.benchmark;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile({"benchmark", "test"})
public class BenchmarkBatchWriter {
    private static final String BENCHMARK_PASSWORD = "benchmark-password-not-for-login";

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> saveAuthors(int authorCount) {
        if (authorCount <= 0) {
            throw new IllegalArgumentException("authorCount must be positive");
        }

        List<User> authors = new ArrayList<>(authorCount);

        for (int authorIndex = 0; authorIndex < authorCount; authorIndex++) {
            User author = new User(
                    "benchmark-" + authorIndex + "@example.invalid",
                    BENCHMARK_PASSWORD,
                    "benchmark-user-" + authorIndex,
                    null
            );
            entityManager.persist(author);
            authors.add(author);
        }

        entityManager.flush();

        List<Long> authorIds = authors.stream()
                .map(User::getId)
                .toList();

        entityManager.clear();
        return authorIds;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePostBatch(
            BenchmarkPostDataFactory dataFactory,
            long startSequence,
            long endSequence,
            List<Long> authorIds
    ) {
        validatePostBatchArguments(startSequence, endSequence, authorIds);

        for (long sequence = startSequence; sequence <= endSequence; sequence++) {
            BenchmarkPostData data = dataFactory.create(sequence);
            Long authorId = authorIds.get(data.authorIndex());
            User author = entityManager.getReference(User.class, authorId);

            Post post = new Post(
                    data.title(),
                    data.content(),
                    null,
                    author
            );

            if (data.deleted()) {
                post.softDelete();
            }

            entityManager.persist(post);
        }

        entityManager.flush();
        entityManager.clear();
    }

    private void validatePostBatchArguments(
            long startSequence,
            long endSequence,
            List<Long> authorIds
    ) {
        if (startSequence <= 0 || endSequence < startSequence) {
            throw new IllegalArgumentException("Invalid post batch sequence range");
        }
        if (authorIds == null || authorIds.isEmpty()) {
            throw new IllegalArgumentException("authorIds must not be empty");
        }
    }
}
