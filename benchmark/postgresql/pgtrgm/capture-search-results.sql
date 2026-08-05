\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

SELECT 'count|common|all|' || COUNT(*)
FROM posts
WHERE deleted_at IS NULL
  AND (
      LOWER(title) LIKE '%qzcommona91x%' ESCAPE '\'
      OR LOWER(content) LIKE '%qzcommona91x%' ESCAPE '\'
  );

SELECT 'count|rare|all|' || COUNT(*)
FROM posts
WHERE deleted_at IS NULL
  AND (
      LOWER(title) LIKE '%tvrarec73z%' ESCAPE '\'
      OR LOWER(content) LIKE '%tvrarec73z%' ESCAPE '\'
  );

SELECT 'count|scope|title|' || COUNT(*)
FROM posts
WHERE deleted_at IS NULL
  AND LOWER(title) LIKE '%ypscopee55m%' ESCAPE '\';

SELECT 'count|scope|content|' || COUNT(*)
FROM posts
WHERE deleted_at IS NULL
  AND LOWER(content) LIKE '%ypscopee55m%' ESCAPE '\';

SELECT 'count|never|all|' || COUNT(*)
FROM posts
WHERE deleted_at IS NULL
  AND (
      LOWER(title) LIKE '%zvneverf46n%' ESCAPE '\'
      OR LOWER(content) LIKE '%zvneverf46n%' ESCAPE '\'
  );

WITH result AS (
    SELECT post_id
    FROM posts
    WHERE deleted_at IS NULL
      AND (
          LOWER(title) LIKE '%qzcommona91x%' ESCAPE '\'
          OR LOWER(content) LIKE '%qzcommona91x%' ESCAPE '\'
      )
    ORDER BY post_id DESC
    LIMIT 11
)
SELECT 'page|common|all|first|' ||
       COALESCE(STRING_AGG(post_id::TEXT, ',' ORDER BY post_id DESC), '')
FROM result;

WITH result AS (
    SELECT post_id
    FROM posts
    WHERE deleted_at IS NULL
      AND (
          LOWER(title) LIKE '%tvrarec73z%' ESCAPE '\'
          OR LOWER(content) LIKE '%tvrarec73z%' ESCAPE '\'
      )
    ORDER BY post_id DESC
    LIMIT 11
)
SELECT 'page|rare|all|first|' ||
       COALESCE(STRING_AGG(post_id::TEXT, ',' ORDER BY post_id DESC), '')
FROM result;

WITH first_page AS (
    SELECT post_id
    FROM posts
    WHERE deleted_at IS NULL
      AND (
          LOWER(title) LIKE '%qzcommona91x%' ESCAPE '\'
          OR LOWER(content) LIKE '%qzcommona91x%' ESCAPE '\'
      )
    ORDER BY post_id DESC
    LIMIT 10
), next_page AS (
    SELECT post_id
    FROM posts
    WHERE deleted_at IS NULL
      AND post_id < (SELECT MIN(post_id) FROM first_page)
      AND (
          LOWER(title) LIKE '%qzcommona91x%' ESCAPE '\'
          OR LOWER(content) LIKE '%qzcommona91x%' ESCAPE '\'
      )
    ORDER BY post_id DESC
    LIMIT 11
)
SELECT 'page|common|all|next|' ||
       COALESCE(STRING_AGG(post_id::TEXT, ',' ORDER BY post_id DESC), '')
FROM next_page;

WITH result AS (
    SELECT post_id
    FROM posts
    WHERE deleted_at IS NULL
      AND LOWER(title) LIKE '%ypscopee55m%' ESCAPE '\'
    ORDER BY post_id DESC
    LIMIT 11
)
SELECT 'page|scope|title|first|' ||
       COALESCE(STRING_AGG(post_id::TEXT, ',' ORDER BY post_id DESC), '')
FROM result;

WITH result AS (
    SELECT post_id
    FROM posts
    WHERE deleted_at IS NULL
      AND LOWER(content) LIKE '%ypscopee55m%' ESCAPE '\'
    ORDER BY post_id DESC
    LIMIT 11
)
SELECT 'page|scope|content|first|' ||
       COALESCE(STRING_AGG(post_id::TEXT, ',' ORDER BY post_id DESC), '')
FROM result;
