\set ON_ERROR_STOP on

DO $verification$
DECLARE
    actual_database TEXT;
    actual_pg_trgm BIGINT;
    actual_fts_columns BIGINT;
    actual_fts_indexes BIGINT;
BEGIN
    SELECT current_database() INTO actual_database;

    SELECT COUNT(*) INTO actual_pg_trgm
    FROM pg_extension
    WHERE extname = 'pg_trgm';

    SELECT COUNT(*) INTO actual_fts_columns
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'posts'
      AND column_name IN ('title_search_vector', 'content_search_vector');

    SELECT COUNT(*) INTO actual_fts_indexes
    FROM pg_indexes
    WHERE schemaname = 'public'
      AND indexname IN (
          'idx_posts_active_title_fts_gin',
          'idx_posts_active_content_fts_gin'
      );

    IF actual_database <> 'community_benchmark_fts' THEN
        RAISE EXCEPTION 'unexpected experiment database: %', actual_database;
    END IF;
    IF actual_pg_trgm <> 0 OR actual_fts_columns <> 0 OR actual_fts_indexes <> 0 THEN
        RAISE EXCEPTION
            'search candidate structure already exists: pg_trgm=%, fts_columns=%, fts_indexes=%',
            actual_pg_trgm,
            actual_fts_columns,
            actual_fts_indexes;
    END IF;
END
$verification$;

SELECT
    current_database() AS database,
    (SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm') AS pg_trgm,
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'posts'
       AND udt_name = 'tsvector') AS tsvector_columns;
