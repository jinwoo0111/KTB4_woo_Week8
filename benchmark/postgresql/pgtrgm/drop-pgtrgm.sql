\set ON_ERROR_STOP on

DROP INDEX IF EXISTS idx_posts_active_title_trgm_gin;
DROP INDEX IF EXISTS idx_posts_active_content_trgm_gin;
DROP EXTENSION IF EXISTS pg_trgm;

ANALYZE posts;
