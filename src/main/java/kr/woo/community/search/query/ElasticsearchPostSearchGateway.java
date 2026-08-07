package kr.woo.community.search.query;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import kr.woo.community.search.index.PostSearchIndexDefinition;
import kr.woo.community.search.index.PostSearchIndexNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class ElasticsearchPostSearchGateway implements PostSearchGateway {

    private static final String POST_ID_FIELD = "post_id";
    private static final String TITLE_FIELD = "title";
    private static final String CONTENT_FIELD = "content";
    private static final String PIT_SHARD_DOC_FIELD = "_shard_doc";

    private final ElasticsearchClient elasticsearchClient;
    private final PostSearchCursorCodec cursorCodec;
    private final String pitKeepAlive;

    public ElasticsearchPostSearchGateway(
            ElasticsearchClient elasticsearchClient,
            PostSearchCursorCodec cursorCodec,
            @Value("${app.search.pit-keep-alive:2m}") String pitKeepAlive
    ) {
        this.elasticsearchClient = Objects.requireNonNull(
                elasticsearchClient,
                "elasticsearchClient must not be null"
        );
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
        if (pitKeepAlive == null || pitKeepAlive.isBlank()) {
            throw new IllegalArgumentException("pitKeepAlive must not be blank");
        }
        this.pitKeepAlive = pitKeepAlive;
    }

    @Override
    public List<PostSearchCandidate> search(PostSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");

        try {
            SearchResponse<Void> response = elasticsearchClient.search(
                    createSearchRequest(criteria),
                    Void.class
            );
            return mapCandidates(response, criteria.sort(), false);
        } catch (IOException | ElasticsearchException e) {
            throw new PostSearchExecutionException(
                    "Failed to search post candidates in Elasticsearch",
                    e
            );
        }
    }

    @Override
    public PostSearchPage searchPage(PostSearchCriteria criteria, String cursor) {
        Objects.requireNonNull(criteria, "criteria");

        DecodedPostSearchCursor decodedCursor = cursor == null
                ? null
                : cursorCodec.decode(cursor, criteria);
        String pitId = decodedCursor == null
                ? openPointInTime()
                : decodedCursor.pitId();
        boolean openedPit = decodedCursor == null;

        try {
            SearchResponse<Void> response = elasticsearchClient.search(
                    createPitSearchRequest(
                            criteria,
                            pitId,
                            decodedCursor == null ? null : decodedCursor.sortValues()
                    ),
                    Void.class
            );
            String latestPitId = response.pitId() == null ? pitId : response.pitId();
            List<PostSearchCandidate> allCandidates = mapCandidates(
                    response,
                    criteria.sort(),
                    true
            );

            boolean hasNext = allCandidates.size() > criteria.limit();
            int pageSize = Math.min(criteria.limit(), allCandidates.size());
            List<PostSearchCandidate> pageCandidates = List.copyOf(
                    allCandidates.subList(0, pageSize)
            );

            if (!hasNext) {
                closePointInTime(latestPitId);
                return new PostSearchPage(pageCandidates, false, null);
            }

            PostSearchCandidate lastCandidate = pageCandidates.getLast();
            String nextCursor = cursorCodec.encode(
                    latestPitId,
                    criteria,
                    lastCandidate.sortValues()
            );
            return new PostSearchPage(pageCandidates, true, nextCursor);
        } catch (IOException | ElasticsearchException e) {
            if (openedPit) {
                closePointInTimeQuietly(pitId);
            }
            throw new PostSearchExecutionException(
                    "Failed to search a PIT post candidate page",
                    e
            );
        } catch (RuntimeException e) {
            if (openedPit) {
                closePointInTimeQuietly(pitId);
            }
            throw e;
        }
    }

    private List<PostSearchCandidate> mapCandidates(
            SearchResponse<Void> response,
            PostSearchSort sort,
            boolean pitSearch
    ) {
        List<PostSearchCandidate> candidates = new ArrayList<>(
                response.hits().hits().size()
        );
        for (Hit<Void> hit : response.hits().hits()) {
            candidates.add(toCandidate(hit, sort, pitSearch));
        }
        return List.copyOf(candidates);
    }

    private SearchRequest createSearchRequest(PostSearchCriteria criteria) {
        SearchRequest.Builder request = baseSearchRequest(criteria, criteria.limit())
                .index(PostSearchIndexNames.READ_ALIAS);
        addBusinessSorts(request, criteria.sort());
        return request.build();
    }

    private SearchRequest createPitSearchRequest(
            PostSearchCriteria criteria,
            String pitId,
            PostSearchSortValues searchAfter
    ) {
        SearchRequest.Builder request = baseSearchRequest(
                criteria,
                criteria.limit() + 1
        ).pit(pit -> pit
                .id(pitId)
                .keepAlive(keepAlive -> keepAlive.time(pitKeepAlive)));

        addBusinessSorts(request, criteria.sort());
        request.sort(sort -> sort.field(field -> field
                .field(PIT_SHARD_DOC_FIELD)
                .order(SortOrder.Asc)));

        if (searchAfter != null) {
            request.searchAfter(toSearchAfter(criteria.sort(), searchAfter));
        }
        return request.build();
    }

    private SearchRequest.Builder baseSearchRequest(
            PostSearchCriteria criteria,
            int size
    ) {
        return new SearchRequest.Builder()
                .query(createQuery(criteria))
                .size(size)
                .source(source -> source.fetch(false))
                .trackScores(true)
                .trackTotalHits(trackHits -> trackHits.enabled(false));
    }

    private void addBusinessSorts(
            SearchRequest.Builder request,
            PostSearchSort sort
    ) {
        if (sort == PostSearchSort.RELEVANCE) {
            request.sort(option -> option.score(score -> score.order(SortOrder.Desc)));
        }
        request.sort(option -> option.field(field -> field
                .field(POST_ID_FIELD)
                .order(SortOrder.Desc)));
    }

    private List<FieldValue> toSearchAfter(
            PostSearchSort sort,
            PostSearchSortValues values
    ) {
        if (values.pitShardDoc() == null) {
            throw new InvalidPostSearchCursorException(
                    "PIT cursor is missing the shard document sort value"
            );
        }

        List<FieldValue> searchAfter = new ArrayList<>();
        if (sort == PostSearchSort.RELEVANCE) {
            if (values.relevanceScore() == null) {
                throw new InvalidPostSearchCursorException(
                        "Relevance cursor is missing the score sort value"
                );
            }
            searchAfter.add(FieldValue.of(values.relevanceScore()));
        }
        searchAfter.add(FieldValue.of(values.postId()));
        searchAfter.add(FieldValue.of(values.pitShardDoc()));
        return List.copyOf(searchAfter);
    }

    private Query createQuery(PostSearchCriteria criteria) {
        return switch (criteria.scope()) {
            case ALL -> MultiMatchQuery.of(query -> query
                    .query(criteria.keyword())
                    .fields(TITLE_FIELD, CONTENT_FIELD)
                    .analyzer(PostSearchIndexDefinition.SEARCH_ANALYZER))
                    ._toQuery();
            case TITLE -> MatchQuery.of(query -> query
                    .field(TITLE_FIELD)
                    .query(criteria.keyword())
                    .analyzer(PostSearchIndexDefinition.SEARCH_ANALYZER))
                    ._toQuery();
            case CONTENT -> MatchQuery.of(query -> query
                    .field(CONTENT_FIELD)
                    .query(criteria.keyword())
                    .analyzer(PostSearchIndexDefinition.SEARCH_ANALYZER))
                    ._toQuery();
        };
    }

    private PostSearchCandidate toCandidate(
            Hit<Void> hit,
            PostSearchSort sort,
            boolean pitSearch
    ) {
        long postId = parsePostId(hit.id());
        List<FieldValue> values = hit.sort();
        int pitValueCount = pitSearch ? 1 : 0;

        if (sort == PostSearchSort.TIME) {
            if (values.size() != 1 + pitValueCount) {
                throw unexpectedSortValues(sort, values);
            }
            long postIdSortValue = requireLong(values.getFirst(), sort);
            Long pitShardDoc = pitSearch ? requireLong(values.get(1), sort) : null;
            return new PostSearchCandidate(
                    postId,
                    hit.score(),
                    new PostSearchSortValues(null, postIdSortValue, pitShardDoc)
            );
        }

        if (values.size() != 2 + pitValueCount) {
            throw unexpectedSortValues(sort, values);
        }
        double scoreSortValue = requireDouble(values.getFirst(), sort);
        long postIdSortValue = requireLong(values.get(1), sort);
        Long pitShardDoc = pitSearch ? requireLong(values.get(2), sort) : null;
        return new PostSearchCandidate(
                postId,
                hit.score(),
                new PostSearchSortValues(scoreSortValue, postIdSortValue, pitShardDoc)
        );
    }

    private String openPointInTime() {
        try {
            return elasticsearchClient.openPointInTime(request -> request
                    .index(PostSearchIndexNames.READ_ALIAS)
                    .keepAlive(keepAlive -> keepAlive.time(pitKeepAlive)))
                    .id();
        } catch (IOException | ElasticsearchException e) {
            throw new PostSearchExecutionException(
                    "Failed to open a post search point in time",
                    e
            );
        }
    }

    private void closePointInTime(String pitId) {
        try {
            var response = elasticsearchClient.closePointInTime(request -> request.id(pitId));
            if (!response.succeeded()) {
                throw new PostSearchExecutionException(
                        "Post search point in time was not closed"
                );
            }
        } catch (IOException | ElasticsearchException e) {
            throw new PostSearchExecutionException(
                    "Failed to close the post search point in time",
                    e
            );
        }
    }

    private void closePointInTimeQuietly(String pitId) {
        try {
            elasticsearchClient.closePointInTime(request -> request.id(pitId));
        } catch (IOException | ElasticsearchException ignored) {
            // Elasticsearch expires the PIT server-side; preserve the original failure.
        }
    }

    private long parsePostId(String documentId) {
        try {
            long postId = Long.parseLong(documentId);
            if (postId <= 0) {
                throw new NumberFormatException("post ID is not positive");
            }
            return postId;
        } catch (NumberFormatException e) {
            throw new PostSearchExecutionException(
                    "Elasticsearch returned an invalid post document ID: " + documentId,
                    e
            );
        }
    }

    private long requireLong(FieldValue value, PostSearchSort sort) {
        if (!value.isLong()) {
            throw unexpectedSortValues(sort, List.of(value));
        }
        return value.longValue();
    }

    private double requireDouble(FieldValue value, PostSearchSort sort) {
        if (value.isDouble()) {
            return value.doubleValue();
        }
        if (value.isLong()) {
            return value.longValue();
        }
        throw unexpectedSortValues(sort, List.of(value));
    }

    private PostSearchExecutionException unexpectedSortValues(
            PostSearchSort sort,
            List<FieldValue> values
    ) {
        return new PostSearchExecutionException(
                "Elasticsearch returned invalid " + sort + " sort values: " + values
        );
    }
}
