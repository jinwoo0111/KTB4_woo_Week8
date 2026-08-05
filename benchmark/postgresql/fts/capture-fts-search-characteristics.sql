\set ON_ERROR_STOP on
\pset pager off

SELECT
    to_tsvector(
        'simple',
        'qzcommona91x tvrarec73z 커뮤니티 검색 Spring SPRING_100% C:\Temp'
    ) AS representative_vector,
    plainto_tsquery('simple', '커뮤니티 검색') AS multi_word_query,
    plainto_tsquery('simple', 'SPRING_100% C:\Temp') AS special_query;

WITH queries AS (
    SELECT
        plainto_tsquery('simple', 'qzcommona91x') AS common_query,
        plainto_tsquery('simple', 'tvrarec73z') AS rare_query,
        plainto_tsquery('simple', 'ypscopee55m') AS scope_query,
        plainto_tsquery('simple', 'qzcommon') AS common_fragment_query,
        plainto_tsquery('simple', 'zvneverf46n') AS never_query
)
SELECT
    COUNT(*) FILTER (
        WHERE p.deleted_at IS NULL
          AND (p.title_search_vector @@ q.common_query
               OR p.content_search_vector @@ q.common_query)
    ) AS common_all,
    COUNT(*) FILTER (
        WHERE p.deleted_at IS NULL
          AND (p.title_search_vector @@ q.rare_query
               OR p.content_search_vector @@ q.rare_query)
    ) AS rare_all,
    COUNT(*) FILTER (
        WHERE p.deleted_at IS NULL
          AND p.title_search_vector @@ q.scope_query
    ) AS scope_title,
    COUNT(*) FILTER (
        WHERE p.deleted_at IS NULL
          AND p.content_search_vector @@ q.scope_query
    ) AS scope_content,
    COUNT(*) FILTER (
        WHERE p.deleted_at IS NULL
          AND (p.title_search_vector @@ q.common_fragment_query
               OR p.content_search_vector @@ q.common_fragment_query)
    ) AS common_fragment_all,
    COUNT(*) FILTER (
        WHERE p.deleted_at IS NULL
          AND (p.title_search_vector @@ q.never_query
               OR p.content_search_vector @@ q.never_query)
    ) AS never_all
FROM posts AS p
CROSS JOIN queries AS q;

\echo 'COMMON marker relevance: every matching document contains the marker once'
WITH search_query AS (
    SELECT plainto_tsquery('simple', 'qzcommona91x') AS query
), ranked AS (
    SELECT
        p.post_id,
        (
            2.0 * ts_rank_cd(p.title_search_vector, q.query)
            + ts_rank_cd(p.content_search_vector, q.query)
        ) AS relevance
    FROM posts AS p
    CROSS JOIN search_query AS q
    WHERE p.deleted_at IS NULL
      AND (p.title_search_vector @@ q.query
           OR p.content_search_vector @@ q.query)
)
SELECT post_id, relevance
FROM ranked
ORDER BY relevance DESC, post_id DESC
FETCH FIRST 11 ROWS ONLY;

\echo 'Natural word relevance: title weight 2.0, content weight 1.0'
WITH search_query AS (
    SELECT plainto_tsquery('simple', '검색') AS query
), ranked AS (
    SELECT
        p.post_id,
        ts_rank_cd(p.title_search_vector, q.query) AS title_rank,
        ts_rank_cd(p.content_search_vector, q.query) AS content_rank,
        (
            2.0 * ts_rank_cd(p.title_search_vector, q.query)
            + ts_rank_cd(p.content_search_vector, q.query)
        ) AS relevance
    FROM posts AS p
    CROSS JOIN search_query AS q
    WHERE p.deleted_at IS NULL
      AND (p.title_search_vector @@ q.query
           OR p.content_search_vector @@ q.query)
)
SELECT post_id, title_rank, content_rank, relevance
FROM ranked
ORDER BY relevance DESC, post_id DESC
FETCH FIRST 11 ROWS ONLY;
