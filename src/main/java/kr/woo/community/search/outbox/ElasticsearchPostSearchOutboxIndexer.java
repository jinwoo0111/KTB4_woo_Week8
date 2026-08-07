package kr.woo.community.search.outbox;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import kr.woo.community.search.document.PostSearchDocument;
import kr.woo.community.search.index.PostSearchIndexNames;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;

@Component
public class ElasticsearchPostSearchOutboxIndexer
        implements PostSearchOutboxIndexer {

    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;

    public ElasticsearchPostSearchOutboxIndexer(
            ElasticsearchClient elasticsearchClient,
            ObjectMapper objectMapper
    ) {
        this.elasticsearchClient = elasticsearchClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public PostSearchIndexingResult apply(ClaimedPostSearchOutboxEvent event) {
        validateEvent(event);
        PostSearchOutboxPayload payload = readPayload(event);
        if (payload.postId() == null || payload.postId() != event.aggregateId()) {
            throw new PostSearchOutboxIndexingException(
                    "Outbox payload post_id does not match aggregate_id"
            );
        }

        BulkOperation operation = switch (event.eventType()) {
            case UPSERT -> createUpsertOperation(event, payload);
            case DELETE -> createDeleteOperation(event);
        };

        try {
            BulkResponse response = elasticsearchClient.bulk(request -> request
                    .index(PostSearchIndexNames.WRITE_ALIAS)
                    .requireAlias(true)
                    .operations(operation));
            if (response.items().size() != 1) {
                throw new PostSearchOutboxIndexingException(
                        "Elasticsearch returned an unexpected outbox bulk item count"
                );
            }
            BulkResponseItem item = response.items().getFirst();
            if (item.status() == 409) {
                return PostSearchIndexingResult.STALE;
            }
            if (item.status() >= 200 && item.status() < 300) {
                return PostSearchIndexingResult.APPLIED;
            }
            if (event.eventType() == PostSearchOutboxEventType.DELETE
                    && item.status() == 404) {
                return PostSearchIndexingResult.APPLIED;
            }
            throw new PostSearchOutboxIndexingException(
                    "Elasticsearch rejected outbox event "
                            + event.eventId() + " with status " + item.status()
                            + ": " + item.error()
            );
        } catch (IOException | ElasticsearchException e) {
            throw new PostSearchOutboxIndexingException(
                    "Failed to apply post search outbox event " + event.eventId(),
                    e
            );
        }
    }

    private BulkOperation createUpsertOperation(
            ClaimedPostSearchOutboxEvent event,
            PostSearchOutboxPayload payload
    ) {
        if (payload.title() == null
                || payload.content() == null
                || payload.createdAt() == null) {
            throw new PostSearchOutboxIndexingException(
                    "UPSERT outbox payload is missing search document fields"
            );
        }
        try {
            PostSearchDocument document = new PostSearchDocument(
                    payload.postId(),
                    payload.title(),
                    payload.content(),
                    LocalDateTime.parse(payload.createdAt()),
                    payload.updatedAt() == null
                            ? null
                            : LocalDateTime.parse(payload.updatedAt())
            );
            return BulkOperation.of(operation -> operation.index(index -> index
                    .id(Long.toString(event.aggregateId()))
                    .version(event.eventId())
                    .versionType(VersionType.ExternalGte)
                    .document(document)));
        } catch (RuntimeException e) {
            throw new PostSearchOutboxIndexingException(
                    "UPSERT outbox payload is invalid",
                    e
            );
        }
    }

    private BulkOperation createDeleteOperation(ClaimedPostSearchOutboxEvent event) {
        return BulkOperation.of(operation -> operation.delete(delete -> delete
                .id(Long.toString(event.aggregateId()))
                .version(event.eventId())
                .versionType(VersionType.ExternalGte)));
    }

    private PostSearchOutboxPayload readPayload(ClaimedPostSearchOutboxEvent event) {
        try {
            return objectMapper.readValue(
                    event.payload(),
                    PostSearchOutboxPayload.class
            );
        } catch (JacksonException e) {
            throw new PostSearchOutboxIndexingException(
                    "Outbox payload is not valid JSON",
                    e
            );
        }
    }

    private void validateEvent(ClaimedPostSearchOutboxEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.eventId() <= 0 || event.aggregateId() <= 0) {
            throw new PostSearchOutboxIndexingException(
                    "Outbox event and aggregate IDs must be positive"
            );
        }
        if (event.payloadVersion() != PostSearchOutboxEvent.CURRENT_PAYLOAD_VERSION) {
            throw new PostSearchOutboxIndexingException(
                    "Unsupported post search outbox payload version: "
                            + event.payloadVersion()
            );
        }
    }
}
