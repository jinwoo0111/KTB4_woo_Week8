package kr.woo.community.search.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PostSearchOutboxProcessor {

    private final PostSearchOutboxClaimService claimService;
    private final PostSearchOutboxIndexer indexer;
    private final PostSearchOutboxStateService stateService;
    private final int batchSize;

    public PostSearchOutboxProcessor(
            PostSearchOutboxClaimService claimService,
            PostSearchOutboxIndexer indexer,
            PostSearchOutboxStateService stateService,
            @Value("${app.search.outbox.batch-size:50}") int batchSize
    ) {
        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        this.claimService = claimService;
        this.indexer = indexer;
        this.stateService = stateService;
        this.batchSize = batchSize;
    }

    public int processBatch(String workerId) {
        List<ClaimedPostSearchOutboxEvent> claimed =
                claimService.claimBatch(workerId, batchSize);
        for (ClaimedPostSearchOutboxEvent event : claimed) {
            processOne(workerId, event);
        }
        return claimed.size();
    }

    private void processOne(
            String workerId,
            ClaimedPostSearchOutboxEvent event
    ) {
        try {
            indexer.apply(event);
            stateService.markProcessed(event.eventId(), workerId);
        } catch (RuntimeException e) {
            stateService.markFailed(event.eventId(), workerId, e);
        }
    }
}
