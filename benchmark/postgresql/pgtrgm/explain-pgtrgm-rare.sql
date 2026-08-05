\set ON_ERROR_STOP on

EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS)
SELECT p.*, u.*
FROM posts p
JOIN users u ON u.user_id = p.user_id
WHERE p.deleted_at IS NULL
  AND (
      LOWER(p.title) LIKE '%tvrarec73z%' ESCAPE '\'
      OR LOWER(p.content) LIKE '%tvrarec73z%' ESCAPE '\'
  )
ORDER BY p.post_id DESC
FETCH FIRST 11 ROWS ONLY;
