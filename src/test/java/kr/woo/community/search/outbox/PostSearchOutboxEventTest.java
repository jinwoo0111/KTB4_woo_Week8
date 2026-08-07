package kr.woo.community.search.outbox;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PostSearchOutboxEventTest {

    @Test
    void transitionsFromPendingToProcessingAndProcessed() {
        PostSearchOutboxEvent event = pendingEvent();
        LocalDateTime claimedAt = LocalDateTime.of(2026, 8, 7, 10, 0);
        LocalDateTime processedAt = claimedAt.plusSeconds(1);

        event.markProcessing("worker-a", claimedAt);
        boolean completed = event.markProcessed("worker-a", processedAt);

        assertThat(completed).isTrue();
        assertThat(event.getStatus()).isEqualTo(PostSearchOutboxStatus.PROCESSED);
        assertThat(event.getAttemptCount()).isOne();
        assertThat(event.getClaimedBy()).isNull();
        assertThat(event.getClaimedAt()).isNull();
        assertThat(event.getProcessedAt()).isEqualTo(processedAt);
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void retriesWithTheProvidedBackoffUntilMaxAttempts() {
        PostSearchOutboxEvent event = pendingEvent();
        LocalDateTime firstAttempt = LocalDateTime.of(2026, 8, 7, 10, 0);

        event.markProcessing("worker-a", firstAttempt);
        boolean retried = event.markFailedAttempt(
                "worker-a",
                firstAttempt.plusSeconds(1),
                firstAttempt.plusSeconds(2),
                2,
                "temporary failure"
        );

        assertThat(retried).isTrue();
        assertThat(event.getStatus()).isEqualTo(PostSearchOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isOne();
        assertThat(event.getAvailableAt()).isEqualTo(firstAttempt.plusSeconds(2));
        assertThat(event.getLastError()).isEqualTo("temporary failure");

        event.markProcessing("worker-b", firstAttempt.plusSeconds(2));
        event.markFailedAttempt(
                "worker-b",
                firstAttempt.plusSeconds(3),
                firstAttempt.plusSeconds(4),
                2,
                "permanent failure"
        );

        assertThat(event.getStatus()).isEqualTo(PostSearchOutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getProcessedAt()).isEqualTo(firstAttempt.plusSeconds(3));
        assertThat(event.getLastError()).isEqualTo("permanent failure");
    }

    @Test
    void ignoresCompletionFromAWorkerThatNoLongerOwnsTheClaim() {
        PostSearchOutboxEvent event = pendingEvent();
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 10, 0);
        event.markProcessing("worker-new", now);

        assertThat(event.markProcessed("worker-old", now.plusSeconds(1))).isFalse();
        assertThat(event.markFailedAttempt(
                "worker-old",
                now.plusSeconds(1),
                now.plusSeconds(2),
                3,
                "late failure"
        )).isFalse();
        assertThat(event.getStatus()).isEqualTo(PostSearchOutboxStatus.PROCESSING);
        assertThat(event.getClaimedBy()).isEqualTo("worker-new");
    }

    @Test
    void recoversATimedOutClaimOrFailsItAtTheAttemptLimit() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 10, 0);
        PostSearchOutboxEvent retryable = pendingEvent();
        retryable.markProcessing("dead-worker", now.minusMinutes(3));
        retryable.recoverTimedOutClaim(now, 2);

        assertThat(retryable.getStatus()).isEqualTo(PostSearchOutboxStatus.PENDING);
        assertThat(retryable.getAvailableAt()).isEqualTo(now);
        assertThat(retryable.getLastError()).isEqualTo("outbox_claim_timed_out");

        retryable.markProcessing("dead-worker-again", now);
        retryable.recoverTimedOutClaim(now.plusMinutes(3), 2);

        assertThat(retryable.getStatus()).isEqualTo(PostSearchOutboxStatus.FAILED);
        assertThat(retryable.getProcessedAt()).isEqualTo(now.plusMinutes(3));
    }

    private PostSearchOutboxEvent pendingEvent() {
        return PostSearchOutboxEvent.pending(
                10L,
                PostSearchOutboxEventType.UPSERT,
                "{\"post_id\":10}"
        );
    }
}
