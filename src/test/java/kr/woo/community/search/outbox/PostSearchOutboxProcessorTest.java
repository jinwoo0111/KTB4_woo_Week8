package kr.woo.community.search.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostSearchOutboxProcessorTest {

    @Mock
    private PostSearchOutboxClaimService claimService;

    @Mock
    private PostSearchOutboxIndexer indexer;

    @Mock
    private PostSearchOutboxStateService stateService;

    @Test
    void processesClaimedEventsInOrderAndMarksAppliedAndStaleEventsProcessed() {
        PostSearchOutboxProcessor processor = processor();
        ClaimedPostSearchOutboxEvent first = event(1L);
        ClaimedPostSearchOutboxEvent second = event(2L);
        when(claimService.claimBatch("worker", 2)).thenReturn(List.of(first, second));
        when(indexer.apply(first)).thenReturn(PostSearchIndexingResult.APPLIED);
        when(indexer.apply(second)).thenReturn(PostSearchIndexingResult.STALE);

        int processed = processor.processBatch("worker");

        assertThat(processed).isEqualTo(2);
        InOrder order = inOrder(indexer, stateService);
        order.verify(indexer).apply(first);
        order.verify(stateService).markProcessed(1L, "worker");
        order.verify(indexer).apply(second);
        order.verify(stateService).markProcessed(2L, "worker");
    }

    @Test
    void recordsFailureAndContinuesWithTheRemainingBatch() {
        PostSearchOutboxProcessor processor = processor();
        ClaimedPostSearchOutboxEvent first = event(1L);
        ClaimedPostSearchOutboxEvent second = event(2L);
        RuntimeException failure = new RuntimeException("temporary Elasticsearch failure");
        when(claimService.claimBatch("worker", 2)).thenReturn(List.of(first, second));
        when(indexer.apply(first)).thenThrow(failure);
        when(indexer.apply(second)).thenReturn(PostSearchIndexingResult.APPLIED);

        int processed = processor.processBatch("worker");

        assertThat(processed).isEqualTo(2);
        verify(stateService).markFailed(1L, "worker", failure);
        verify(stateService).markProcessed(2L, "worker");
    }

    private PostSearchOutboxProcessor processor() {
        return new PostSearchOutboxProcessor(
                claimService,
                indexer,
                stateService,
                2
        );
    }

    private ClaimedPostSearchOutboxEvent event(long eventId) {
        return new ClaimedPostSearchOutboxEvent(
                eventId,
                10L,
                PostSearchOutboxEventType.UPSERT,
                1,
                "{\"post_id\":10}",
                1
        );
    }
}
