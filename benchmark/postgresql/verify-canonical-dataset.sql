\set ON_ERROR_STOP on

DO $verification$
DECLARE
    actual_users BIGINT;
    actual_posts BIGINT;
    actual_active_posts BIGINT;
    actual_deleted_posts BIGINT;
    actual_comments BIGINT;
    actual_post_likes BIGINT;
    actual_author_min BIGINT;
    actual_author_max BIGINT;
    actual_common BIGINT;
    actual_medium BIGINT;
    actual_rare BIGINT;
    actual_fixed BIGINT;
    actual_scope_title BIGINT;
    actual_scope_content BIGINT;
    actual_scope_both BIGINT;
    actual_never BIGINT;
    actual_deleted_markers BIGINT;
    actual_length_300_799 BIGINT;
    actual_length_800_1999 BIGINT;
    actual_length_2000_7999 BIGINT;
    actual_length_8000_15999 BIGINT;
    actual_length_16000_32000 BIGINT;
    actual_invalid_defaults BIGINT;
    actual_invalid_nulls BIGINT;
    actual_orphans BIGINT;
BEGIN
    SELECT COUNT(*) INTO actual_users FROM users;
    SELECT COUNT(*) INTO actual_posts FROM posts;
    SELECT COUNT(*) INTO actual_active_posts FROM posts WHERE deleted_at IS NULL;
    SELECT COUNT(*) INTO actual_deleted_posts FROM posts WHERE deleted_at IS NOT NULL;
    SELECT COUNT(*) INTO actual_comments FROM comments;
    SELECT COUNT(*) INTO actual_post_likes FROM post_likes;

    SELECT MIN(post_count), MAX(post_count)
    INTO actual_author_min, actual_author_max
    FROM (
        SELECT user_id, COUNT(*) AS post_count
        FROM posts
        GROUP BY user_id
    ) author_counts;

    SELECT COUNT(*) INTO actual_common
    FROM posts
    WHERE deleted_at IS NULL AND content LIKE '%qzcommona91x%';
    SELECT COUNT(*) INTO actual_medium
    FROM posts
    WHERE deleted_at IS NULL AND content LIKE '%rxmediumb82y%';
    SELECT COUNT(*) INTO actual_rare
    FROM posts
    WHERE deleted_at IS NULL AND content LIKE '%tvrarec73z%';
    SELECT COUNT(*) INTO actual_fixed
    FROM posts
    WHERE deleted_at IS NULL AND content LIKE '%wxfixedd64k%';
    SELECT COUNT(*) INTO actual_scope_title
    FROM posts
    WHERE deleted_at IS NULL AND title LIKE '%ypscopee55m%';
    SELECT COUNT(*) INTO actual_scope_content
    FROM posts
    WHERE deleted_at IS NULL AND content LIKE '%ypscopee55m%';
    SELECT COUNT(*) INTO actual_scope_both
    FROM posts
    WHERE deleted_at IS NULL
      AND title LIKE '%ypscopee55m%'
      AND content LIKE '%ypscopee55m%';
    SELECT COUNT(*) INTO actual_never
    FROM posts
    WHERE title LIKE '%zvneverf46n%' OR content LIKE '%zvneverf46n%';

    SELECT COUNT(*) INTO actual_deleted_markers
    FROM posts
    WHERE deleted_at IS NOT NULL
      AND (
          title LIKE ANY (ARRAY[
              '%qzcommona91x%', '%rxmediumb82y%', '%tvrarec73z%',
              '%wxfixedd64k%', '%ypscopee55m%', '%zvneverf46n%'
          ])
          OR content LIKE ANY (ARRAY[
              '%qzcommona91x%', '%rxmediumb82y%', '%tvrarec73z%',
              '%wxfixedd64k%', '%ypscopee55m%', '%zvneverf46n%'
          ])
      );

    SELECT
        COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 300 AND 799),
        COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 800 AND 1999),
        COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 2000 AND 7999),
        COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 8000 AND 15999),
        COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 16000 AND 32000)
    INTO
        actual_length_300_799,
        actual_length_800_1999,
        actual_length_2000_7999,
        actual_length_8000_15999,
        actual_length_16000_32000
    FROM posts;

    SELECT COUNT(*) INTO actual_invalid_defaults
    FROM posts
    WHERE content_image IS NOT NULL
       OR like_count <> 0
       OR comment_count <> 0
       OR view_count <> 0;

    SELECT
        (SELECT COUNT(*) FROM users
         WHERE email IS NULL OR password IS NULL OR nickname IS NULL
            OR role IS NULL OR created_at IS NULL)
        +
        (SELECT COUNT(*) FROM posts
         WHERE user_id IS NULL OR title IS NULL OR content IS NULL
            OR created_at IS NULL)
    INTO actual_invalid_nulls;

    SELECT COUNT(*) INTO actual_orphans
    FROM posts p
    LEFT JOIN users u ON u.user_id = p.user_id
    WHERE u.user_id IS NULL;

    IF actual_users <> 100 OR actual_posts <> 100000 THEN
        RAISE EXCEPTION 'unexpected users/posts: %/%', actual_users, actual_posts;
    END IF;
    IF actual_active_posts <> 95000 OR actual_deleted_posts <> 5000 THEN
        RAISE EXCEPTION 'unexpected active/deleted posts: %/%',
            actual_active_posts, actual_deleted_posts;
    END IF;
    IF actual_comments <> 0 OR actual_post_likes <> 0 THEN
        RAISE EXCEPTION 'unexpected comments/post_likes: %/%',
            actual_comments, actual_post_likes;
    END IF;
    IF actual_author_min <> 1000 OR actual_author_max <> 1000 THEN
        RAISE EXCEPTION 'unexpected per-author post range: %/%',
            actual_author_min, actual_author_max;
    END IF;
    IF actual_common <> 9500 OR actual_medium <> 950 OR actual_rare <> 95
       OR actual_fixed <> 10 THEN
        RAISE EXCEPTION 'unexpected marker counts: common=%, medium=%, rare=%, fixed=%',
            actual_common, actual_medium, actual_rare, actual_fixed;
    END IF;
    IF actual_scope_title <> 950 OR actual_scope_content <> 950
       OR actual_scope_both <> 950 THEN
        RAISE EXCEPTION 'unexpected scope counts: title=%, content=%, both=%',
            actual_scope_title, actual_scope_content, actual_scope_both;
    END IF;
    IF actual_never <> 0 OR actual_deleted_markers <> 0 THEN
        RAISE EXCEPTION 'unexpected never/deleted marker counts: %/%',
            actual_never, actual_deleted_markers;
    END IF;
    IF actual_length_300_799 <> 60000
       OR actual_length_800_1999 <> 30000
       OR actual_length_2000_7999 <> 9000
       OR actual_length_8000_15999 <> 0
       OR actual_length_16000_32000 <> 1000 THEN
        RAISE EXCEPTION 'unexpected content length distribution: %, %, %, %, %',
            actual_length_300_799,
            actual_length_800_1999,
            actual_length_2000_7999,
            actual_length_8000_15999,
            actual_length_16000_32000;
    END IF;
    IF actual_invalid_defaults <> 0 OR actual_invalid_nulls <> 0
       OR actual_orphans <> 0 THEN
        RAISE EXCEPTION 'invalid defaults/nulls/orphans: %/%/%',
            actual_invalid_defaults, actual_invalid_nulls, actual_orphans;
    END IF;
END
$verification$;

SELECT
    (SELECT COUNT(*) FROM users) AS users,
    COUNT(*) AS posts,
    COUNT(*) FILTER (WHERE deleted_at IS NULL) AS active_posts,
    COUNT(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted_posts,
    author_distribution.min_posts_per_author,
    author_distribution.max_posts_per_author
FROM posts
CROSS JOIN (
    SELECT
        MIN(post_count) AS min_posts_per_author,
        MAX(post_count) AS max_posts_per_author
    FROM (
        SELECT COUNT(*) AS post_count
        FROM posts
        GROUP BY user_id
    ) counts
) author_distribution
GROUP BY
    author_distribution.min_posts_per_author,
    author_distribution.max_posts_per_author;

SELECT
    COUNT(*) FILTER (
        WHERE deleted_at IS NULL AND content LIKE '%qzcommona91x%'
    ) AS common,
    COUNT(*) FILTER (
        WHERE deleted_at IS NULL AND content LIKE '%rxmediumb82y%'
    ) AS medium,
    COUNT(*) FILTER (
        WHERE deleted_at IS NULL AND content LIKE '%tvrarec73z%'
    ) AS rare,
    COUNT(*) FILTER (
        WHERE deleted_at IS NULL AND content LIKE '%wxfixedd64k%'
    ) AS fixed,
    COUNT(*) FILTER (
        WHERE deleted_at IS NULL AND title LIKE '%ypscopee55m%'
    ) AS scope_title,
    COUNT(*) FILTER (
        WHERE deleted_at IS NULL AND content LIKE '%ypscopee55m%'
    ) AS scope_content
FROM posts;

SELECT
    COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 300 AND 799) AS length_300_799,
    COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 800 AND 1999) AS length_800_1999,
    COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 2000 AND 7999) AS length_2000_7999,
    COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 8000 AND 15999) AS length_8000_15999,
    COUNT(*) FILTER (WHERE LENGTH(content) BETWEEN 16000 AND 32000) AS length_16000_32000
FROM posts;
