package kr.woo.community.search.index;

import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class PostSearchIndexDefinition {

    public static final String INDEX_ANALYZER = "community_nori_index";
    public static final String SEARCH_ANALYZER = "community_nori_search";

    private static final String DEFINITION_RESOURCE =
            "/elasticsearch/post-search-index.json";

    private PostSearchIndexDefinition() {
    }

    public static CreateIndexRequest createIndexRequest(String indexName) {
        return createIndexRequest(indexName, null, null);
    }

    public static CreateIndexRequest createInitialIndexRequest(
            String indexName,
            String readAlias,
            String writeAlias
    ) {
        requireName(readAlias, "readAlias");
        requireName(writeAlias, "writeAlias");
        if (readAlias.equals(writeAlias)) {
            throw new IllegalArgumentException("readAlias and writeAlias must be different");
        }

        return createIndexRequest(indexName, readAlias, writeAlias);
    }

    private static CreateIndexRequest createIndexRequest(
            String indexName,
            String readAlias,
            String writeAlias
    ) {
        requireName(indexName, "indexName");

        try (InputStream definition = PostSearchIndexDefinition.class
                .getResourceAsStream(DEFINITION_RESOURCE)) {
            Objects.requireNonNull(
                    definition,
                    "Missing Elasticsearch index definition: " + DEFINITION_RESOURCE
            );

            CreateIndexRequest.Builder builder = new CreateIndexRequest.Builder()
                    .index(indexName);
            builder.withJson(definition);
            if (readAlias != null) {
                builder.aliases(readAlias, alias -> alias);
                builder.aliases(writeAlias, alias -> alias.isWriteIndex(true));
            }
            return builder.build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Elasticsearch index definition", e);
        }
    }

    private static void requireName(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
