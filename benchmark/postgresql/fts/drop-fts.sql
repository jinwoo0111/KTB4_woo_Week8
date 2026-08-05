\set ON_ERROR_STOP on

DROP INDEX IF EXISTS idx_posts_active_title_fts_gin;
DROP INDEX IF EXISTS idx_posts_active_content_fts_gin;

ALTER TABLE posts
    DROP COLUMN IF EXISTS title_search_vector,
    DROP COLUMN IF EXISTS content_search_vector;

ANALYZE posts;
