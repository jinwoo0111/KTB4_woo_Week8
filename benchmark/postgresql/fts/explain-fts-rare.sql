\set ON_ERROR_STOP on

EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS)
WITH search_query AS (
    SELECT plainto_tsquery('simple', 'tvrarec73z') AS query
)
SELECT p.post_id
FROM posts AS p
CROSS JOIN search_query AS q
WHERE p.deleted_at IS NULL
  AND (
      p.title_search_vector @@ q.query
      OR p.content_search_vector @@ q.query
  )
ORDER BY p.post_id DESC
FETCH FIRST 11 ROWS ONLY;
