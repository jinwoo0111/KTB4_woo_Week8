package kr.woo.community.search.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PostSearchOutboxClaimService {

    private final PostSearchOutboxRepository outboxRepository;
    private final Duration claimTimeout;
    private final int maxAttempts;

    public PostSearchOutboxClaimService(
            PostSearchOutboxRepository outboxRepository,
            @Value("${app.search.outbox.claim-timeout:PT2M}") Duration claimTimeout,
            @Value("${app.search.outbox.max-attempts:5}") int maxAttempts
    ) {
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()) {
            throw new IllegalArgumentException("claimTimeout must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.outboxRepository = outboxRepository;
        this.claimTimeout = claimTimeout;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public List<ClaimedPostSearchOutboxEvent> claimBatch(
            String workerId,
            int batchSize
    ) {
        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        LocalDateTime now = LocalDateTime.now();
        recoverTimedOutClaims(now, batchSize);

        List<PostSearchOutboxEvent> events =
                outboxRepository.findClaimableForUpdateSkipLocked(
                        PostSearchOutboxStatus.PENDING,
                        now,
                        PageRequest.of(0, batchSize)
                );
        for (PostSearchOutboxEvent event : events) {
            event.markProcessing(workerId, now);
        }
        return events.stream()
                .map(ClaimedPostSearchOutboxEvent::from)
                .toList();
    }

    private void recoverTimedOutClaims(LocalDateTime now, int batchSize) {
        List<PostSearchOutboxEvent> timedOut =
                outboxRepository.findTimedOutForUpdateSkipLocked(
                        PostSearchOutboxStatus.PROCESSING,
                        now.minus(claimTimeout),
                        PageRequest.of(0, batchSize)
                );
        for (PostSearchOutboxEvent event : timedOut) {
            event.recoverTimedOutClaim(now, maxAttempts);
        }
    }
}
