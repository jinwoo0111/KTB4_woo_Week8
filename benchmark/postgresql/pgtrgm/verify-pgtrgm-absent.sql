\set ON_ERROR_STOP on

DO $verification$
DECLARE
    actual_database TEXT;
    actual_migration_count BIGINT;
    actual_pg_trgm BIGINT;
    actual_candidate_indexes BIGINT;
    actual_tsvector BIGINT;
BEGIN
    SELECT current_database() INTO actual_database;
    SELECT COUNT(*) INTO actual_migration_count
    FROM flyway_schema_history
    WHERE success;
    SELECT COUNT(*) INTO actual_pg_trgm
    FROM pg_extension
    WHERE extname = 'pg_trgm';
    SELECT COUNT(*) INTO actual_candidate_indexes
    FROM pg_indexes
    WHERE schemaname = 'public'
      AND indexname IN (
          'idx_posts_active_title_trgm_gin',
          'idx_posts_active_content_trgm_gin'
      );
    SELECT COUNT(*) INTO actual_tsvector
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'posts'
      AND data_type = 'tsvector';

    IF actual_database <> 'community_benchmark_pgtrgm' THEN
        RAISE EXCEPTION 'unexpected experiment database: %', actual_database;
    END IF;
    IF actual_migration_count <> 1 THEN
        RAISE EXCEPTION 'expected Flyway V1 only, successful migrations=%',
            actual_migration_count;
    END IF;
    IF actual_pg_trgm <> 0 OR actual_candidate_indexes <> 0
       OR actual_tsvector <> 0 THEN
        RAISE EXCEPTION
            'search candidate structure already exists: pg_trgm=%, trigram indexes=%, tsvector=%',
            actual_pg_trgm,
            actual_candidate_indexes,
            actual_tsvector;
    END IF;
END
$verification$;

SELECT
    current_database() AS database,
    (SELECT COUNT(*) FROM flyway_schema_history WHERE success) AS migrations,
    (SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm') AS pg_trgm,
    (SELECT COUNT(*) FROM posts) AS posts;
