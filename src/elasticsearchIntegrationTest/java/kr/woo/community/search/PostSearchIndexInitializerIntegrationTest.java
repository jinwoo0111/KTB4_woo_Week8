package kr.woo.community.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch.indices.AliasDefinition;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import kr.woo.community.search.index.PostSearchIndexDefinition;
import kr.woo.community.search.index.PostSearchIndexInitializationResult;
import kr.woo.community.search.index.PostSearchIndexInitializer;
import kr.woo.community.search.index.PostSearchIndexNames;
import kr.woo.community.search.index.PostSearchIndexStateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ElasticsearchTestcontainersConfiguration.class)
@ActiveProfiles("test")
class PostSearchIndexInitializerIntegrationTest {

    private static final String UNEXPECTED_INDEX = "community-posts-v000002-test";

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    private PostSearchIndexInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        deleteInitialIndexIfPresent();
        initializer = new PostSearchIndexInitializer(elasticsearchClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteInitialIndexIfPresent();
    }

    @Test
    void createsThePhysicalIndexAndBothAliasesAtomically() throws Exception {
        PostSearchIndexInitializationResult result = initializer.initialize();

        assertThat(result).isEqualTo(PostSearchIndexInitializationResult.CREATED);
        assertThat(indexExists(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)).isTrue();

        Map<String, AliasDefinition> aliases = aliasesOnInitialIndex();
        assertThat(aliases).containsOnlyKeys(
                PostSearchIndexNames.READ_ALIAS,
                PostSearchIndexNames.WRITE_ALIAS
        );
        assertThat(aliases.get(PostSearchIndexNames.READ_ALIAS).isWriteIndex())
                .isNotEqualTo(true);
        assertThat(aliases.get(PostSearchIndexNames.WRITE_ALIAS).isWriteIndex())
                .isTrue();
    }

    @Test
    void treatsASecondInitializationOfTheValidStateAsANoOp() {
        assertThat(initializer.initialize())
                .isEqualTo(PostSearchIndexInitializationResult.CREATED);

        assertThat(initializer.initialize())
                .isEqualTo(PostSearchIndexInitializationResult.ALREADY_INITIALIZED);
    }

    @Test
    void rejectsAPartiallyCreatedStateWithoutAddingMissingAliases() throws Exception {
        elasticsearchClient.indices().create(
                PostSearchIndexDefinition.createIndexRequest(
                        PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
                )
        );

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(PostSearchIndexStateException.class)
                .hasMessageContaining("Incomplete post search index state");

        assertThat(aliasExists(PostSearchIndexNames.READ_ALIAS)).isFalse();
        assertThat(aliasExists(PostSearchIndexNames.WRITE_ALIAS)).isFalse();
    }

    @Test
    void rejectsAnInvalidWriteAliasWithoutRepairingIt() throws Exception {
        initializer.initialize();
        elasticsearchClient.indices().putAlias(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
                .name(PostSearchIndexNames.WRITE_ALIAS)
                .isWriteIndex(false));

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(PostSearchIndexStateException.class)
                .hasMessageContaining("Only the post search write alias");

        assertThat(aliasesOnInitialIndex()
                .get(PostSearchIndexNames.WRITE_ALIAS)
                .isWriteIndex()).isFalse();
    }

    @Test
    void rejectsACompleteAliasTopologyWithAnInvalidMapping() throws Exception {
        elasticsearchClient.indices().create(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
                .aliases(PostSearchIndexNames.READ_ALIAS, alias -> alias)
                .aliases(
                        PostSearchIndexNames.WRITE_ALIAS,
                        alias -> alias.isWriteIndex(true)
                )
                .mappings(mapping -> mapping
                        .dynamic(DynamicMapping.Strict)
                        .properties("post_id", property -> property.long_(type -> type))));

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(PostSearchIndexStateException.class)
                .hasMessageContaining("does not match the index contract");

        assertThat(elasticsearchClient.indices()
                .getMapping(request -> request.index(
                        PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
                ))
                .get(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
                .mappings()
                .properties()).containsOnlyKeys("post_id");
    }

    @Test
    void rejectsAliasesThatAlsoPointToAnUnexpectedPhysicalIndex() throws Exception {
        initializer.initialize();
        elasticsearchClient.indices().create(
                PostSearchIndexDefinition.createIndexRequest(UNEXPECTED_INDEX)
        );
        elasticsearchClient.indices().putAlias(request -> request
                .index(UNEXPECTED_INDEX)
                .name(PostSearchIndexNames.READ_ALIAS));
        elasticsearchClient.indices().putAlias(request -> request
                .index(UNEXPECTED_INDEX)
                .name(PostSearchIndexNames.WRITE_ALIAS)
                .isWriteIndex(false));

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(PostSearchIndexStateException.class)
                .hasMessageContaining("must point only to");

        assertThat(indexExists(UNEXPECTED_INDEX)).isTrue();
        assertThat(aliasExists(PostSearchIndexNames.READ_ALIAS)).isTrue();
        assertThat(aliasExists(PostSearchIndexNames.WRITE_ALIAS)).isTrue();
    }

    @Test
    void rejectsChangedIndexSettingsWithoutRepairingThem() throws Exception {
        initializer.initialize();
        elasticsearchClient.indices().putSettings(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
                .settings(settings -> settings.refreshInterval(time -> time.time("2s"))));

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(PostSearchIndexStateException.class)
                .hasMessageContaining("refreshInterval=2s");

        assertThat(elasticsearchClient.indices()
                .getSettings(request -> request.index(
                        PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
                ))
                .get(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
                .settings()
                .index()
                .refreshInterval()
                ._toJsonString()).isEqualTo("2s");
    }

    private Map<String, AliasDefinition> aliasesOnInitialIndex() throws Exception {
        GetAliasResponse response = elasticsearchClient.indices().getAlias(request -> request
                .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX));
        IndexAliases indexAliases = response.get(
                PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
        );
        return indexAliases.aliases();
    }

    private boolean indexExists(String indexName) throws Exception {
        return elasticsearchClient.indices().exists(request -> request
                .index(indexName)).value();
    }

    private boolean aliasExists(String aliasName) throws Exception {
        return elasticsearchClient.indices().existsAlias(request -> request
                .name(aliasName)).value();
    }

    private void deleteInitialIndexIfPresent() throws Exception {
        if (indexExists(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)) {
            elasticsearchClient.indices().delete(request -> request
                    .index(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX));
        }
        if (indexExists(UNEXPECTED_INDEX)) {
            elasticsearchClient.indices().delete(request -> request.index(UNEXPECTED_INDEX));
        }
    }
}
