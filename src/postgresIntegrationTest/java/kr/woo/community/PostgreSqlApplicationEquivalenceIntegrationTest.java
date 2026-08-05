package kr.woo.community;

import kr.woo.community.entity.Comment;
import kr.woo.community.entity.Post;
import kr.woo.community.entity.PostLike;
import kr.woo.community.entity.User;
import kr.woo.community.repository.CommentRepository;
import kr.woo.community.repository.PostLikeRepository;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgres-integration-test")
@Transactional
class PostgreSqlApplicationEquivalenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private PostLikeRepository postLikeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void appliesFlywayV1AndPersistsEntityRelationshipsWithPostgreSqlSequences() {
        Boolean migrationSucceeded = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'",
                Boolean.class
        );
        Integer sequenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_sequences "
                        + "WHERE schemaname = current_schema() AND increment_by = 50 "
                        + "AND sequencename IN ('users_seq', 'posts_seq', 'comments_seq', 'post_likes_seq')",
                Integer.class
        );

        User author = saveUser("relation@test.com", "관계작성자");
        Post post = postRepository.saveAndFlush(new Post("관계 제목", "관계 본문", null, author));
        Comment comment = commentRepository.saveAndFlush(new Comment(post, author, "댓글 내용"));
        PostLike postLike = postLikeRepository.saveAndFlush(new PostLike(post, author));

        assertThat(migrationSucceeded).isTrue();
        assertThat(sequenceCount).isEqualTo(4);
        assertThat(author.getId()).isNotNull();
        assertThat(post.getId()).isNotNull();
        assertThat(comment.getId()).isNotNull();
        assertThat(postLike.getId()).isNotNull();
        assertThat(comment.getPost().getId()).isEqualTo(post.getId());
        assertThat(comment.getAuthor().getId()).isEqualTo(author.getId());
        assertThat(postLike.getPost().getId()).isEqualTo(post.getId());
        assertThat(postLike.getUser().getId()).isEqualTo(author.getId());
        assertThat(post.getCreatedAt()).isNotNull();
        assertThat(post.getLikeCount()).isZero();
        assertThat(post.getCommentCount()).isZero();
        assertThat(post.getViewCount()).isZero();
    }

    @Test
    void enforcesPostLikeUniqueConstraint() {
        User firstUser = saveUser("unique@test.com", "고유닉네임");
        User secondUser = saveUser("second@test.com", "두번째닉네임");
        Post post = postRepository.saveAndFlush(new Post("고유 제약", "본문", null, firstUser));
        postLikeRepository.saveAndFlush(new PostLike(post, secondUser));

        assertThatThrownBy(() -> postLikeRepository.saveAndFlush(new PostLike(post, secondUser)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesEmailUniqueConstraint() {
        saveUser("duplicate@test.com", "첫번째닉네임");

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                new User("duplicate@test.com", "password", "두번째닉네임", null)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesNicknameUniqueConstraint() {
        saveUser("first-nickname@test.com", "중복닉네임");

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                new User("second-nickname@test.com", "password", "중복닉네임", null)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesCounterCheckConstraint() {
        User author = saveUser("check@test.com", "체크작성자");
        Post post = postRepository.saveAndFlush(new Post("체크 제약", "본문", null, author));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE posts SET like_count = -1 WHERE post_id = ?",
                post.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsPhysicalDeletionOfReferencedParent() {
        User author = saveUser("foreign-key@test.com", "외래키작성자");
        postRepository.saveAndFlush(new Post("외래 키", "본문", null, author));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM users WHERE user_id = ?",
                author.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preservesActivePostCommentCursorAndViewCountQueries() {
        User author = saveUser("query@test.com", "조회작성자");
        Post oldestPost = postRepository.save(new Post("첫 게시글", "본문", null, author));
        Post deletedPost = postRepository.save(new Post("삭제 게시글", "본문", null, author));
        deletedPost.softDelete();
        Post newestPost = postRepository.saveAndFlush(new Post("최신 게시글", "본문", null, author));

        Comment firstComment = commentRepository.save(new Comment(oldestPost, author, "첫 댓글"));
        Comment deletedComment = commentRepository.save(new Comment(oldestPost, author, "삭제 댓글"));
        deletedComment.softDelete();
        Comment lastComment = commentRepository.saveAndFlush(new Comment(oldestPost, author, "마지막 댓글"));

        List<Post> firstPage = postRepository.findPostsByCursor(null, PageRequest.of(0, 10));
        List<Post> nextPage = postRepository.findPostsByCursor(newestPost.getId(), PageRequest.of(0, 10));
        List<Comment> comments = commentRepository.findByPost_IdAndDeletedAtIsNullOrderByIdAsc(
                oldestPost.getId()
        );
        int updatedRows = postRepository.increaseViewCount(oldestPost.getId());
        int deletedPostUpdatedRows = postRepository.increaseViewCount(deletedPost.getId());

        assertThat(firstPage).extracting(Post::getId)
                .containsExactly(newestPost.getId(), oldestPost.getId());
        assertThat(nextPage).extracting(Post::getId).containsExactly(oldestPost.getId());
        assertThat(comments).extracting(Comment::getId)
                .containsExactly(firstComment.getId(), lastComment.getId());
        assertThat(updatedRows).isEqualTo(1);
        assertThat(deletedPostUpdatedRows).isZero();
        assertThat(postRepository.findById(oldestPost.getId()).orElseThrow().getViewCount()).isEqualTo(1);
    }

    @Test
    void preservesCaseInsensitiveScopeAndDeletedPostSearchContract() throws Exception {
        User author = saveUser("search@test.com", "검색작성자");
        Post titleMatch = postRepository.save(new Post("Spring 검색", "JPA 본문", null, author));
        Post contentMatch = postRepository.save(new Post("JPA 검색", "SPRING 본문", null, author));
        Post deletedMatch = postRepository.save(new Post("삭제된 Spring", "본문", null, author));
        deletedMatch.softDelete();
        postRepository.flush();

        mockMvc.perform(get("/posts").param("keyword", "spring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("posts_success"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(contentMatch.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(titleMatch.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "spring")
                        .param("scope", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(titleMatch.getId()));
    }

    @Test
    void treatsLikeSpecialCharactersLiterallyAndKeepsCursorResponseContract() throws Exception {
        User author = saveUser("special@test.com", "특수문자작성자");
        Post oldestMatch = postRepository.save(new Post(
                "Spring_100% C:\\Temp 첫 번째",
                "본문",
                null,
                author
        ));
        Post secondMatch = postRepository.save(new Post(
                "Spring_100% C:\\Temp 두 번째",
                "본문",
                null,
                author
        ));
        Post newestMatch = postRepository.save(new Post(
                "Spring_100% C:\\Temp 세 번째",
                "본문",
                null,
                author
        ));
        postRepository.save(new Post("SpringX1000 CXXTemp", "와일드카드 오탐 후보", null, author));
        postRepository.flush();

        mockMvc.perform(get("/posts")
                        .param("keyword", "SPRING_100% C:\\TEMP")
                        .param("scope", "title")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.has_next").value(true))
                .andExpect(jsonPath("$.data.next_cursor").value(secondMatch.getId()))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(newestMatch.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(secondMatch.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "SPRING_100% C:\\TEMP")
                        .param("scope", "title")
                        .param("cursor", secondMatch.getId().toString())
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false))
                .andExpect(jsonPath("$.data.next_cursor").doesNotExist())
                .andExpect(jsonPath("$.data.posts[0].post_id").value(oldestMatch.getId()));
    }

    private User saveUser(String email, String nickname) {
        return userRepository.saveAndFlush(new User(email, "password", nickname, null));
    }
}
