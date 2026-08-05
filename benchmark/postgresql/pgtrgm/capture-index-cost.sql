\set ON_ERROR_STOP on

SELECT
    current_database() AS database,
    pg_relation_size('posts') AS posts_heap_bytes,
    pg_indexes_size('posts') AS all_posts_indexes_bytes,
    pg_total_relation_size('posts') AS posts_total_bytes,
    pg_size_pretty(pg_relation_size('posts')) AS posts_heap,
    pg_size_pretty(pg_indexes_size('posts')) AS all_posts_indexes,
    pg_size_pretty(pg_total_relation_size('posts')) AS posts_total;

SELECT
    index_definition.indexname,
    pg_relation_size(index_definition.indexname::regclass) AS index_bytes,
    pg_size_pretty(pg_relation_size(index_definition.indexname::regclass)) AS index_size,
    index_definition.indexdef
FROM pg_indexes AS index_definition
WHERE index_definition.schemaname = 'public'
  AND index_definition.tablename = 'posts'
ORDER BY index_definition.indexname;

SELECT
    index_stats.indexrelname AS indexname,
    index_stats.idx_scan,
    index_stats.idx_tup_read,
    index_stats.idx_tup_fetch
FROM pg_stat_user_indexes AS index_stats
WHERE index_stats.schemaname = 'public'
  AND index_stats.relname = 'posts'
ORDER BY index_stats.indexrelname;

SHOW gin_pending_list_limit;
