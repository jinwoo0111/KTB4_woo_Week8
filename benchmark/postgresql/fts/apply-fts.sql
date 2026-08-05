\set ON_ERROR_STOP on
\timing on

ALTER TABLE posts
    ADD COLUMN title_search_vector TSVECTOR
        GENERATED ALWAYS AS (
            to_tsvector('simple'::regconfig, COALESCE(title, ''))
        ) STORED,
    ADD COLUMN content_search_vector TSVECTOR
        GENERATED ALWAYS AS (
            to_tsvector('simple'::regconfig, COALESCE(content, ''))
        ) STORED;

CREATE INDEX idx_posts_active_title_fts_gin
    ON posts USING GIN (title_search_vector)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_posts_active_content_fts_gin
    ON posts USING GIN (content_search_vector)
    WHERE deleted_at IS NULL;

ANALYZE posts;

\timing off
