\set ON_ERROR_STOP on
\timing on

CREATE EXTENSION pg_trgm;

CREATE INDEX idx_posts_active_title_trgm_gin
    ON posts USING GIN (LOWER(title) gin_trgm_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_posts_active_content_trgm_gin
    ON posts USING GIN (LOWER(content) gin_trgm_ops)
    WHERE deleted_at IS NULL;

ANALYZE posts;

\timing off
