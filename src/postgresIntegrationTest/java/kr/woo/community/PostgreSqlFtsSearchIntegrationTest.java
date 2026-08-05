package kr.woo.community;

import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.search.mode=fts")
@AutoConfigureMockMvc
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgres-integration-test")
class PostgreSqlFtsSearchIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void searchesWholeLexemesAndPreservesScopeSoftDeleteAndIdCursorContract()
            throws Exception {
        applyFtsStructure();

        User author = userRepository.saveAndFlush(
                new User("fts@test.com", "password", "fts작성자", null)
        );

        Post titleMatch = postRepository.save(
                new Post("Spring database", "JPA 본문", null, author)
        );
        Post contentMatch = postRepository.save(
                new Post("JPA 제목", "SPRING DATABASE 본문", null, author)
        );
        Post compoundWord = postRepository.save(
                new Post("SpringBoot database", "본문", null, author)
        );
        Post koreanMatch = postRepository.save(
                new Post("커뮤니티 검색", "테스트 본문", null, author)
        );
        Post deletedMatch = postRepository.save(
                new Post("삭제된 Spring database", "본문", null, author)
        );
        deletedMatch.softDelete();
        postRepository.saveAndFlush(deletedMatch);

        Post oldestCursorMatch = postRepository.save(
                new Post("Cursorword 첫 번째", "본문", null, author)
        );
        Post middleCursorMatch = postRepository.save(
                new Post("Cursorword 두 번째", "본문", null, author)
        );
        Post newestCursorMatch = postRepository.save(
                new Post("Cursorword 세 번째", "본문", null, author)
        );
        postRepository.flush();

        mockMvc.perform(get("/posts")
                        .param("keyword", "spring database")
                        .param("scope", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(contentMatch.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(titleMatch.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "spring")
                        .param("scope", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(titleMatch.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "spring")
                        .param("scope", "content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(contentMatch.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "springboot")
                        .param("scope", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(compoundWord.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "sprin")
                        .param("scope", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));

        mockMvc.perform(get("/posts")
                        .param("keyword", "커뮤니티 검색")
                        .param("scope", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(koreanMatch.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "cursorword")
                        .param("scope", "title")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.has_next").value(true))
                .andExpect(jsonPath("$.data.next_cursor").value(middleCursorMatch.getId()))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(newestCursorMatch.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(middleCursorMatch.getId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", "cursorword")
                        .param("scope", "title")
                        .param("cursor", middleCursorMatch.getId().toString())
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(oldestCursorMatch.getId()));

        assertThat(generatedVectorColumnCount()).isEqualTo(2);
        assertThat(validFtsGinIndexCount()).isEqualTo(2);
    }

    private void applyFtsStructure() {
        jdbcTemplate.execute("""
                ALTER TABLE posts
                    ADD COLUMN title_search_vector TSVECTOR
                        GENERATED ALWAYS AS (
                            to_tsvector('simple'::regconfig, COALESCE(title, ''))
                        ) STORED,
                    ADD COLUMN content_search_vector TSVECTOR
                        GENERATED ALWAYS AS (
                            to_tsvector('simple'::regconfig, COALESCE(content, ''))
                        ) STORED
                """);
        jdbcTemplate.execute("""
                CREATE INDEX idx_posts_active_title_fts_gin
                    ON posts USING GIN (title_search_vector)
                    WHERE deleted_at IS NULL
                """);
        jdbcTemplate.execute("""
                CREATE INDEX idx_posts_active_content_fts_gin
                    ON posts USING GIN (content_search_vector)
                    WHERE deleted_at IS NULL
                """);
        jdbcTemplate.execute("ANALYZE posts");
    }

    private Integer generatedVectorColumnCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'posts'
                  AND column_name IN (
                      'title_search_vector',
                      'content_search_vector'
                  )
                  AND udt_name = 'tsvector'
                  AND is_generated = 'ALWAYS'
                """, Integer.class);
    }

    private Integer validFtsGinIndexCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_index AS index_state
                JOIN pg_class AS index_relation
                  ON index_relation.oid = index_state.indexrelid
                JOIN pg_am AS access_method
                  ON access_method.oid = index_relation.relam
                JOIN pg_opclass AS operator_class
                  ON operator_class.oid = index_state.indclass[0]
                WHERE index_relation.relname IN (
                    'idx_posts_active_title_fts_gin',
                    'idx_posts_active_content_fts_gin'
                )
                  AND access_method.amname = 'gin'
                  AND operator_class.opcname = 'tsvector_ops'
                  AND index_state.indisvalid
                  AND index_state.indisready
                  AND pg_get_expr(index_state.indpred, index_state.indrelid)
                      = '(deleted_at IS NULL)'
                """, Integer.class);
    }
}
