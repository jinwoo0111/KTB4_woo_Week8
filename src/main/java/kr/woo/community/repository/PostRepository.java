package kr.woo.community.repository;

import kr.woo.community.entity.Post;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {
    @Query("""
       SELECT p
       FROM Post p
       JOIN FETCH p.author
       WHERE p.deletedAt IS NULL
       AND (:cursor IS NULL OR p.id < :cursor)
       ORDER BY p.id DESC
""")
    List<Post> findPostsByCursor(
            @Param("cursor") Long cursor,
            Pageable pageable
    );

    @Query("""
       SELECT p
       FROM Post p
       JOIN FETCH p.author
       WHERE p.deletedAt IS NULL
       AND (:cursor IS NULL OR p.id < :cursor)
       AND LOWER(p.title) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
       ORDER BY p.id DESC
""")
    List<Post> searchPostsByTitle(
            @Param("keyword") String keyword,
            @Param("cursor") Long cursor,
            Pageable pageable
    );

    @Query("""
       SELECT p
       FROM Post p
       JOIN FETCH p.author
       WHERE p.deletedAt IS NULL
       AND (:cursor IS NULL OR p.id < :cursor)
       AND LOWER(p.content) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
       ORDER BY p.id DESC
""")
    List<Post> searchPostsByContent(
            @Param("keyword") String keyword,
            @Param("cursor") Long cursor,
            Pageable pageable
    );

    @Query("""
       SELECT p
       FROM Post p
       JOIN FETCH p.author
       WHERE p.deletedAt IS NULL
       AND (:cursor IS NULL OR p.id < :cursor)
       AND (
           LOWER(p.title) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
           OR LOWER(p.content) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
       )
       ORDER BY p.id DESC
""")
    List<Post> searchPostsByTitleOrContent(
            @Param("keyword") String keyword,
            @Param("cursor") Long cursor,
            Pageable pageable
    );

    @Query("""
       SELECT p
       FROM Post p
       JOIN FETCH p.author
       WHERE p.id IN :postIds
""")
    List<Post> findAllByIdsWithAuthor(@Param("postIds") List<Long> postIds);

    @Query("""
       SELECT p
       FROM Post p
       JOIN FETCH p.author
       WHERE p.id IN :postIds
       AND p.deletedAt IS NULL
""")
    List<Post> findAllActiveByIdsWithAuthor(@Param("postIds") List<Long> postIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
       UPDATE Post p
       SET p.viewCount = p.viewCount + 1
       WHERE p.id = :postId
       AND p.deletedAt IS NULL
""")
    int increaseViewCount(@Param("postId") Long postId);
}
