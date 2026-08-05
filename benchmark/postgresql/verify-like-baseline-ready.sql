\set ON_ERROR_STOP on

DO $verification$
DECLARE
    actual_database TEXT;
    actual_flyway_v1 BIGINT;
    actual_users BIGINT;
    actual_posts BIGINT;
    actual_active_posts BIGINT;
    actual_deleted_posts BIGINT;
    actual_common BIGINT;
    actual_pg_trgm BIGINT;
    actual_tsvector BIGINT;
BEGIN
    SELECT current_database() INTO actual_database;
    SELECT COUNT(*) INTO actual_flyway_v1
    FROM flyway_schema_history
    WHERE version = '1' AND success;
    SELECT COUNT(*) INTO actual_users FROM users;
    SELECT COUNT(*) INTO actual_posts FROM posts;
    SELECT COUNT(*) INTO actual_active_posts FROM posts WHERE deleted_at IS NULL;
    SELECT COUNT(*) INTO actual_deleted_posts FROM posts WHERE deleted_at IS NOT NULL;
    SELECT COUNT(*) INTO actual_common
    FROM posts
    WHERE deleted_at IS NULL
      AND content LIKE '%qzcommona91x%';
    SELECT COUNT(*) INTO actual_pg_trgm
    FROM pg_extension
    WHERE extname = 'pg_trgm';
    SELECT COUNT(*) INTO actual_tsvector
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND udt_name = 'tsvector';

    IF actual_database <> 'community_benchmark' THEN
        RAISE EXCEPTION 'unexpected database: %', actual_database;
    END IF;
    IF actual_flyway_v1 <> 1 THEN
        RAISE EXCEPTION 'Flyway V1 verification failed: %', actual_flyway_v1;
    END IF;
    IF actual_users <> 100 THEN
        RAISE EXCEPTION 'unexpected users count: %', actual_users;
    END IF;
    IF actual_posts <> 100000 THEN
        RAISE EXCEPTION 'unexpected posts count: %', actual_posts;
    END IF;
    IF actual_active_posts <> 95000 OR actual_deleted_posts <> 5000 THEN
        RAISE EXCEPTION 'unexpected active/deleted counts: %/%',
            actual_active_posts,
            actual_deleted_posts;
    END IF;
    IF actual_common <> 9500 THEN
        RAISE EXCEPTION 'unexpected common marker count: %', actual_common;
    END IF;
    IF actual_pg_trgm <> 0 OR actual_tsvector <> 0 THEN
        RAISE EXCEPTION 'search experiment structure already exists: pg_trgm=%, tsvector=%',
            actual_pg_trgm,
            actual_tsvector;
    END IF;
END
$verification$;

SELECT
    current_database() AS database,
    current_setting('server_version') AS postgresql_version,
    current_setting('shared_buffers') AS shared_buffers,
    current_setting('work_mem') AS work_mem,
    current_setting('maintenance_work_mem') AS maintenance_work_mem,
    current_setting('effective_cache_size') AS effective_cache_size,
    current_setting('max_connections') AS max_connections,
    current_setting('random_page_cost') AS random_page_cost,
    current_setting('jit') AS jit;

SELECT
    relname,
    n_live_tup,
    last_analyze IS NOT NULL AS analyzed,
    last_autoanalyze IS NOT NULL AS autoanalyzed
FROM pg_stat_user_tables
WHERE relname IN ('users', 'posts', 'comments', 'post_likes')
ORDER BY relname;

SELECT
    COUNT(*) AS users,
    (SELECT COUNT(*) FROM posts) AS posts,
    (SELECT COUNT(*) FROM posts WHERE deleted_at IS NULL) AS active_posts,
    (SELECT COUNT(*) FROM posts WHERE deleted_at IS NOT NULL) AS deleted_posts,
    (SELECT COUNT(*) FROM posts
     WHERE deleted_at IS NULL
       AND content LIKE '%qzcommona91x%') AS common_content_matches
FROM users;
