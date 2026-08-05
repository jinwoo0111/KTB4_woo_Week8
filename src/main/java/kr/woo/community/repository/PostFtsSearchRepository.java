package kr.woo.community.repository;

import kr.woo.community.entity.Post;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class PostFtsSearchRepository {

    private static final String TITLE_MATCH =
            "p.title_search_vector @@ search_query.query";
    private static final String CONTENT_MATCH =
            "p.content_search_vector @@ search_query.query";
    private static final String ALL_MATCH =
            "(" + TITLE_MATCH + " OR " + CONTENT_MATCH + ")";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PostRepository postRepository;

    public List<Post> searchByTitle(String keyword, Long cursor, int limit) {
        return search(keyword, cursor, limit, TITLE_MATCH);
    }

    public List<Post> searchByContent(String keyword, Long cursor, int limit) {
        return search(keyword, cursor, limit, CONTENT_MATCH);
    }

    public List<Post> searchByTitleOrContent(String keyword, Long cursor, int limit) {
        return search(keyword, cursor, limit, ALL_MATCH);
    }

    private List<Post> search(String keyword, Long cursor, int limit, String matchExpression) {
        String sql = """
                WITH search_query AS (
                    SELECT plainto_tsquery('simple', :keyword) AS query
                )
                SELECT p.post_id
                FROM posts AS p
                CROSS JOIN search_query
                WHERE p.deleted_at IS NULL
                  AND (CAST(:cursor AS BIGINT) IS NULL OR p.post_id < :cursor)
                  AND %s
                ORDER BY p.post_id DESC
                LIMIT :limit
                """.formatted(matchExpression);

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("keyword", keyword)
                .addValue("cursor", cursor)
                .addValue("limit", limit);

        List<Long> postIds = jdbcTemplate.queryForList(sql, parameters, Long.class);
        if (postIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Post> postsById = postRepository.findAllByIdsWithAuthor(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));

        return postIds.stream()
                .map(postsById::get)
                .toList();
    }
}
