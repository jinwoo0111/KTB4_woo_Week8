package kr.woo.community.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.AliasDefinition;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public class PostSearchIndexInitializer {

    private final ElasticsearchClient elasticsearchClient;

    public PostSearchIndexInitializer(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = Objects.requireNonNull(
                elasticsearchClient,
                "elasticsearchClient must not be null"
        );
    }

    public PostSearchIndexInitializationResult initialize() {
        try {
            boolean physicalIndexExists = indexExists(
                    PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
            );
            boolean readAliasExists = aliasExists(PostSearchIndexNames.READ_ALIAS);
            boolean writeAliasExists = aliasExists(PostSearchIndexNames.WRITE_ALIAS);

            if (!physicalIndexExists && !readAliasExists && !writeAliasExists) {
                createInitialIndex();
                return PostSearchIndexInitializationResult.CREATED;
            }

            if (!physicalIndexExists || !readAliasExists || !writeAliasExists) {
                throw incompleteState(
                        physicalIndexExists,
                        readAliasExists,
                        writeAliasExists
                );
            }

            verifyAliasTopology();
            verifyIndexContract();
            return PostSearchIndexInitializationResult.ALREADY_INITIALIZED;
        } catch (PostSearchIndexStateException e) {
            throw e;
        } catch (IOException e) {
            throw new PostSearchIndexStateException(
                    "Failed to initialize the post search index",
                    e
            );
        }
    }

    private boolean indexExists(String indexName) throws IOException {
        return elasticsearchClient.indices()
                .exists(request -> request.index(indexName))
                .value();
    }

    private boolean aliasExists(String aliasName) throws IOException {
        return elasticsearchClient.indices()
                .existsAlias(request -> request.name(aliasName))
                .value();
    }

    private void createInitialIndex() throws IOException {
        CreateIndexResponse response = elasticsearchClient.indices().create(
                PostSearchIndexDefinition.createInitialIndexRequest(
                        PostSearchIndexNames.INITIAL_PHYSICAL_INDEX,
                        PostSearchIndexNames.READ_ALIAS,
                        PostSearchIndexNames.WRITE_ALIAS
                )
        );
        if (!response.acknowledged() || !response.shardsAcknowledged()) {
            throw new PostSearchIndexStateException(
                    "Post search index creation was not fully acknowledged"
            );
        }
    }

    private void verifyAliasTopology() throws IOException {
        GetAliasResponse response = elasticsearchClient.indices().getAlias(request -> request
                .name(
                        PostSearchIndexNames.READ_ALIAS,
                        PostSearchIndexNames.WRITE_ALIAS
                ));

        if (!response.aliases().keySet().equals(
                java.util.Set.of(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
        )) {
            throw new PostSearchIndexStateException(
                    "Post search aliases must point only to "
                            + PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
            );
        }

        IndexAliases indexAliases = response.get(
                PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
        );
        Map<String, AliasDefinition> aliases = indexAliases.aliases();
        if (!aliases.keySet().equals(java.util.Set.of(
                PostSearchIndexNames.READ_ALIAS,
                PostSearchIndexNames.WRITE_ALIAS
        ))) {
            throw new PostSearchIndexStateException(
                    "Post search index must have exactly the read and write aliases"
            );
        }

        AliasDefinition readAlias = aliases.get(PostSearchIndexNames.READ_ALIAS);
        AliasDefinition writeAlias = aliases.get(PostSearchIndexNames.WRITE_ALIAS);
        if (Boolean.TRUE.equals(readAlias.isWriteIndex())
                || !Boolean.TRUE.equals(writeAlias.isWriteIndex())) {
            throw new PostSearchIndexStateException(
                    "Only the post search write alias may be the write index"
            );
        }
    }

    private void verifyIndexContract() throws IOException {
        TypeMapping mapping = elasticsearchClient.indices()
                .getMapping(request -> request.index(
                        PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
                ))
                .get(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX)
                .mappings();

        Map<String, Property> properties = mapping.properties();
        boolean validMapping = mapping.dynamic() == DynamicMapping.Strict
                && (mapping.source() == null
                || !Boolean.FALSE.equals(mapping.source().enabled()))
                && properties.keySet().equals(java.util.Set.of(
                        "post_id",
                        "title",
                        "content",
                        "created_at",
                        "updated_at"
                ))
                && properties.get("post_id").isLong()
                && isExpectedTextProperty(properties.get("title"))
                && isExpectedTextProperty(properties.get("content"))
                && isExpectedAuditDateProperty(properties.get("created_at"))
                && isExpectedAuditDateProperty(properties.get("updated_at"));

        IndexState indexState = elasticsearchClient.indices()
                .getSettings(request -> request.index(
                        PostSearchIndexNames.INITIAL_PHYSICAL_INDEX
                ))
                .get(PostSearchIndexNames.INITIAL_PHYSICAL_INDEX);
        IndexSettings settings = indexState.settings();
        if (settings != null && settings.index() != null) {
            settings = settings.index();
        }
        boolean validSettings = settings != null
                && "1".equals(settings.numberOfShards())
                && "0".equals(settings.numberOfReplicas())
                && settings.refreshInterval() != null
                && "1s".equals(settings.refreshInterval()._toJsonString());

        if (!validMapping || !validSettings) {
            throw new PostSearchIndexStateException(
                    "Existing post search index does not match the index contract: mapping="
                            + validMapping
                            + ", shards=" + (settings == null
                            ? null : settings.numberOfShards())
                            + ", replicas=" + (settings == null
                            ? null : settings.numberOfReplicas())
                            + ", refreshInterval=" + (settings == null
                            || settings.refreshInterval() == null
                            ? null : settings.refreshInterval()._toJsonString())
            );
        }
    }

    private boolean isExpectedTextProperty(Property property) {
        return property != null
                && property.isText()
                && PostSearchIndexDefinition.INDEX_ANALYZER.equals(
                        property.text().analyzer()
                )
                && PostSearchIndexDefinition.SEARCH_ANALYZER.equals(
                        property.text().searchAnalyzer()
                );
    }

    private boolean isExpectedAuditDateProperty(Property property) {
        return property != null
                && property.isDate()
                && "strict_date_optional_time_nanos".equals(property.date().format())
                && Boolean.FALSE.equals(property.date().index())
                && Boolean.FALSE.equals(property.date().docValues());
    }

    private PostSearchIndexStateException incompleteState(
            boolean physicalIndexExists,
            boolean readAliasExists,
            boolean writeAliasExists
    ) {
        return new PostSearchIndexStateException(
                "Incomplete post search index state: physicalIndex="
                        + physicalIndexExists
                        + ", readAlias=" + readAliasExists
                        + ", writeAlias=" + writeAliasExists
        );
    }
}
