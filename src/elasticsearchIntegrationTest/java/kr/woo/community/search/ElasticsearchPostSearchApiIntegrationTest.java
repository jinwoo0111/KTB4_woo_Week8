package kr.woo.community.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.jayway.jsonpath.JsonPath;
import kr.woo.community.entity.Post;
import kr.woo.community.entity.User;
import kr.woo.community.repository.PostRepository;
import kr.woo.community.repository.UserRepository;
import kr.woo.community.search.document.PostSearchDocument;
import kr.woo.community.search.index.PostSearchIndexInitializer;
import kr.woo.community.search.index.PostSearchIndexNames;
import kr.woo.community.search.query.DecodedPostSearchCursor;
import kr.woo.community.search.query.PostSearchCriteria;
import kr.woo.community.search.query.PostSearchCursorCodec;
import kr.woo.community.search.query.PostSearchScope;
import kr.woo.community.search.query.PostSearchSort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ElasticsearchTestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.search.backend=elasticsearch")
class ElasticsearchPostSearchApiIntegrationTest {

    private static final String KEYWORD = "대한민국 개발자";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private PostSearchCursorCodec cursorCodec;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @BeforeEach
    void setUp() throws Exception {
        deleteInitialIndexIfPresent();
        new PostSearchIndexInitializer(elasticsearchClient).initialize();
        postRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteInitialIndexIfPresent();
    }

    @Test
    @DisplayName("Elasticsearch API 검색은 Opaque cursor로 다음 페이지를 연결한다")
    void connectsElasticsearchSearchAndOpaqueCursorToExistingApi() throws Exception {
        User author = saveUser("es-api@test.com", "Elasticsearch작성자");
        Post oldest = saveAndIndexPost("대한민국 개발자 커뮤니티 1", author);
        Post second = saveAndIndexPost("대한민국 개발자 커뮤니티 2", author);
        Post third = saveAndIndexPost("대한민국 개발자 커뮤니티 3", author);
        Post newest = saveAndIndexPost("대한민국 개발자 커뮤니티 4", author);
        refresh();

        MvcResult firstResult = mockMvc.perform(get("/posts")
                        .param("keyword", KEYWORD)
                        .param("sort", "time")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.has_next").value(true))
                .andExpect(jsonPath("$.data.next_cursor").isString())
                .andExpect(jsonPath("$.data.next_cursor", matchesPattern("^(?!\\d+$).+\\..+$")))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(newest.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(third.getId()))
                .andExpect(jsonPath("$.data.search.requested_sort").value("time"))
                .andExpect(jsonPath("$.data.search.effective_sort").value("time"))
                .andExpect(jsonPath("$.data.search.backend").value("elasticsearch"))
                .andExpect(jsonPath("$.data.search.degraded").value(false))
                .andReturn();

        String cursor = JsonPath.read(
                firstResult.getResponse().getContentAsString(),
                "$.data.next_cursor"
        );

        mockMvc.perform(get("/posts")
                        .param("keyword", KEYWORD)
                        .param("sort", "time")
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.has_next").value(false))
                .andExpect(jsonPath("$.data.next_cursor").doesNotExist())
                .andExpect(jsonPath("$.data.posts[0].post_id").value(second.getId()))
                .andExpect(jsonPath("$.data.posts[1].post_id").value(oldest.getId()));
    }

    @Test
    @DisplayName("Elasticsearch 후보 hydration은 PostgreSQL에서 삭제된 게시글을 제외한다")
    void hydratesOnlyActivePostgreSqlPosts() throws Exception {
        User author = saveUser("es-active@test.com", "활성작성자");
        Post active = saveAndIndexPost("대한민국 개발자 활성 게시글", author);
        Post deleted = saveAndIndexPost("대한민국 개발자 삭제 게시글", author);
        deleted.softDelete();
        postRepository.saveAndFlush(deleted);
        refresh();

        mockMvc.perform(get("/posts")
                        .param("keyword", KEYWORD)
                        .param("sort", "time")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.posts[0].post_id").value(active.getId()));
    }

    @Test
    @DisplayName("검색 cursor 위변조는 400, 닫힌 PIT 재사용은 503을 반환한다")
    void mapsInvalidCursorAndClosedPitToApiErrors() throws Exception {
        User author = saveUser("es-errors@test.com", "오류작성자");
        saveAndIndexPost("대한민국 개발자 오류 게시글 1", author);
        saveAndIndexPost("대한민국 개발자 오류 게시글 2", author);
        saveAndIndexPost("대한민국 개발자 오류 게시글 3", author);
        refresh();

        MvcResult firstResult = mockMvc.perform(get("/posts")
                        .param("keyword", KEYWORD)
                        .param("sort", "time")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andReturn();
        String cursor = JsonPath.read(
                firstResult.getResponse().getContentAsString(),
                "$.data.next_cursor"
        );

        mockMvc.perform(get("/posts")
                        .param("keyword", KEYWORD)
                        .param("sort", "time")
                        .param("size", "2")
                        .param("cursor", cursor + "tampered"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_pagination_parameter"));

        PostSearchCriteria criteria = new PostSearchCriteria(
                KEYWORD,
                PostSearchScope.ALL,
                PostSearchSort.TIME,
                2
        );
        DecodedPostSearchCursor decoded = cursorCodec.decode(cursor, criteria);
        elasticsearchClient.closePointInTime(request -> request.id(decoded.pitId()));

        mockMvc.perform(get("/posts")
                        .param("keyword", KEYWORD)
                        .param("sort", "time")
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("search_temporarily_unavailable"));
    }

    private User saveUser(String email, String nickname) {
        return userRepository.saveAndFlush(
                new User(email, "password", nickname, null)
        );
    }

    private Post saveAndIndexPost(String title, User author) throws Exception {
        Post post = postRepository.saveAndFlush(
                new Post(title, "한국어 검색을 위한 본문입니다.", null, author)
        );
        PostSearchDocument document = new PostSearchDocument(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
        elasticsearchClient.index(request -> request
                .index(PostSearchIndexNames.WRITE_ALIAS)
                .requireAlias(true)
                .id(document.getDocumentId())
                .document(document));
        return post;
    }

    private void refresh() throws Exception {
        elasticsearchClient.indices().refresh(request -> request
                .index(PostSearchIndexNames.WRITE_ALIAS));
    }

    private void deleteInitialIndexIfPresent() throws Exception {
        boolean exists = elasticsearchClient.indices().exists(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)).value();
        if (exists) {
            elasticsearchClient.indices().delete(request -> request
                    .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX));
        }
    }
}
