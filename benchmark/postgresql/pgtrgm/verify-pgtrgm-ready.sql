\set ON_ERROR_STOP on

DO $verification$
DECLARE
    actual_database TEXT;
    actual_pg_trgm BIGINT;
    actual_valid_indexes BIGINT;
    actual_tsvector BIGINT;
BEGIN
    SELECT current_database() INTO actual_database;
    SELECT COUNT(*) INTO actual_pg_trgm
    FROM pg_extension
    WHERE extname = 'pg_trgm';
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
          'idx_posts_active_title_trgm_gin',
          'idx_posts_active_content_trgm_gin'
      )
      AND access_method.amname = 'gin'
      AND operator_class.opcname = 'gin_trgm_ops'
      AND index_state.indisvalid
      AND index_state.indisready
      AND pg_get_expr(index_state.indpred, index_state.indrelid)
          = '(deleted_at IS NULL)';
    SELECT COUNT(*) INTO actual_tsvector
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'posts'
      AND data_type = 'tsvector';

    IF actual_database <> 'community_benchmark_pgtrgm' THEN
        RAISE EXCEPTION 'unexpected experiment database: %', actual_database;
    END IF;
    IF actual_pg_trgm <> 1 THEN
        RAISE EXCEPTION 'expected pg_trgm extension, actual=%', actual_pg_trgm;
    END IF;
    IF actual_valid_indexes <> 2 THEN
        RAISE EXCEPTION 'expected two valid partial expression GIN indexes, actual=%',
            actual_valid_indexes;
    END IF;
    IF actual_tsvector <> 0 THEN
        RAISE EXCEPTION 'FTS structure must not exist, tsvector columns=%',
            actual_tsvector;
    END IF;
END
$verification$;

SELECT
    extension.extversion AS pg_trgm_version,
    index_definition.indexname,
    index_definition.indexdef,
    pg_size_pretty(pg_relation_size(index_definition.indexname::regclass)) AS index_size
FROM pg_extension AS extension
CROSS JOIN pg_indexes AS index_definition
WHERE extension.extname = 'pg_trgm'
  AND index_definition.schemaname = 'public'
  AND index_definition.indexname IN (
      'idx_posts_active_title_trgm_gin',
      'idx_posts_active_content_trgm_gin'
  )
ORDER BY index_definition.indexname;
