\set ON_ERROR_STOP on

DO $verification$
DECLARE
    actual_database TEXT;
    actual_pg_trgm BIGINT;
    actual_generated_columns BIGINT;
    actual_valid_indexes BIGINT;
BEGIN
    SELECT current_database() INTO actual_database;

    SELECT COUNT(*) INTO actual_pg_trgm
    FROM pg_extension
    WHERE extname = 'pg_trgm';

    SELECT COUNT(*) INTO actual_generated_columns
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'posts'
      AND column_name IN ('title_search_vector', 'content_search_vector')
      AND udt_name = 'tsvector'
      AND is_generated = 'ALWAYS';

    SELECT COUNT(*) INTO actual_valid_indexes
    FROM pg_index AS index_state
    JOIN pg_class AS index_relation
      ON index_relation.oid = index_state.indexrelid
    JOIN pg_class AS table_relation
      ON table_relation.oid = index_state.indrelid
    JOIN pg_am AS access_method
      ON access_method.oid = index_relation.relam
    JOIN pg_opclass AS operator_class
      ON operator_class.oid = index_state.indclass[0]
    WHERE table_relation.relname = 'posts'
      AND index_relation.relname IN (
          'idx_posts_active_title_fts_gin',
          'idx_posts_active_content_fts_gin'
      )
      AND access_method.amname = 'gin'
      AND operator_class.opcname = 'tsvector_ops'
      AND index_state.indisvalid
      AND index_state.indisready
      AND pg_get_expr(index_state.indpred, index_state.indrelid)
          = '(deleted_at IS NULL)';

    IF actual_database <> 'community_benchmark_fts' THEN
        RAISE EXCEPTION 'unexpected experiment database: %', actual_database;
    END IF;
    IF actual_pg_trgm <> 0 THEN
        RAISE EXCEPTION 'pg_trgm must not exist in FTS database, actual=%', actual_pg_trgm;
    END IF;
    IF actual_generated_columns <> 2 THEN
        RAISE EXCEPTION 'expected two generated tsvector columns, actual=%',
            actual_generated_columns;
    END IF;
    IF actual_valid_indexes <> 2 THEN
        RAISE EXCEPTION 'expected two valid partial FTS GIN indexes, actual=%',
            actual_valid_indexes;
    END IF;
END
$verification$;

SELECT
    column_name,
    udt_name,
    is_generated,
    generation_expression
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'posts'
  AND column_name IN ('title_search_vector', 'content_search_vector')
ORDER BY ordinal_position;

SELECT
    indexname,
    indexdef,
    pg_relation_size(indexname::regclass) AS index_bytes,
    pg_size_pretty(pg_relation_size(indexname::regclass)) AS index_size
FROM pg_indexes
WHERE schemaname = 'public'
  AND indexname IN (
      'idx_posts_active_title_fts_gin',
      'idx_posts_active_content_fts_gin'
  )
ORDER BY indexname;
