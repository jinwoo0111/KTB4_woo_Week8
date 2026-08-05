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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.pg-trgm-equivalence-test=true")
@AutoConfigureMockMvc
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgres-integration-test")
class PostgreSqlPgTrgmSearchEquivalenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preservesSearchResultsAfterApplyingPgTrgmPartialExpressionGinIndexes() throws Exception {
        User author = userRepository.saveAndFlush(
                new User("pgtrgm@test.com", "password", "pgtrgm작성자", null)
        );

        postRepository.save(new Post("Spring 검색", "JPA 본문", null, author));
        postRepository.save(new Post("JPA 검색", "SPRING 본문", null, author));
        Post deletedPost = postRepository.save(
                new Post("삭제된 Spring", "SPRING 삭제 본문", null, author)
        );
        deletedPost.softDelete();

        Post oldestSpecial = postRepository.save(new Post(
                "Spring_100% C:\\Temp 첫 번째",
                "본문",
                null,
                author
        ));
        Post secondSpecial = postRepository.save(new Post(
                "Spring_100% C:\\Temp 두 번째",
                "본문",
                null,
                author
        ));
        postRepository.save(new Post(
                "Spring_100% C:\\Temp 세 번째",
                "본문",
                null,
                author
        ));
        postRepository.save(new Post(
                "SpringX1000 CXXTemp",
                "LIKE 특수문자 오탐 후보",
                null,
                author
        ));
        postRepository.save(new Post("AB 두 글자 검색", "본문", null, author));
        postRepository.flush();

        assertThat(extensionCount()).isZero();
        assertThat(candidateIndexCount()).isZero();

        Map<String, String> before = captureResponses(secondSpecial.getId());

        jdbcTemplate.execute("CREATE EXTENSION pg_trgm");
        jdbcTemplate.execute("""
                CREATE INDEX idx_posts_active_title_trgm_gin
                    ON posts USING GIN (LOWER(title) gin_trgm_ops)
                    WHERE deleted_at IS NULL
                """);
        jdbcTemplate.execute("""
                CREATE INDEX idx_posts_active_content_trgm_gin
                    ON posts USING GIN (LOWER(content) gin_trgm_ops)
                    WHERE deleted_at IS NULL
                """);
        jdbcTemplate.execute("ANALYZE posts");

        Map<String, String> after = captureResponses(secondSpecial.getId());

        assertThat(after).isEqualTo(before);
        assertThat(extensionCount()).isOne();
        assertThat(candidateIndexCount()).isEqualTo(2);
        assertThat(validPgTrgmGinIndexCount()).isEqualTo(2);
        assertThat(after.get("special-next"))
                .contains("\"post_id\":" + oldestSpecial.getId())
                .contains("\"has_next\":false");
    }

    private Map<String, String> captureResponses(Long specialCursor) throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("all", perform(get("/posts")
                .param("keyword", "SPRING")
                .param("scope", "all")));
        responses.put("title", perform(get("/posts")
                .param("keyword", "SPRING")
                .param("scope", "title")));
        responses.put("content", perform(get("/posts")
                .param("keyword", "SPRING")
                .param("scope", "content")));
        responses.put("special-first", perform(get("/posts")
                .param("keyword", "SPRING_100% C:\\TEMP")
                .param("scope", "title")
                .param("size", "2")));
        responses.put("special-next", perform(get("/posts")
                .param("keyword", "SPRING_100% C:\\TEMP")
                .param("scope", "title")
                .param("cursor", specialCursor.toString())
                .param("size", "2")));
        responses.put("two-character", perform(get("/posts")
                .param("keyword", "AB")
                .param("scope", "title")));
        responses.put("no-result", perform(get("/posts")
                .param("keyword", "검색결과없음")
                .param("scope", "all")));
        return responses;
    }

    private String perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private Integer extensionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'",
                Integer.class
        );
    }

    private Integer candidateIndexCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN (
                      'idx_posts_active_title_trgm_gin',
                      'idx_posts_active_content_trgm_gin'
                  )
                """, Integer.class);
    }

    private Integer validPgTrgmGinIndexCount() {
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
                    'idx_posts_active_title_trgm_gin',
                    'idx_posts_active_content_trgm_gin'
                )
                  AND access_method.amname = 'gin'
                  AND operator_class.opcname = 'gin_trgm_ops'
                  AND index_state.indisvalid
                  AND index_state.indisready
                  AND pg_get_expr(index_state.indpred, index_state.indrelid)
                      = '(deleted_at IS NULL)'
                """, Integer.class);
    }
}
