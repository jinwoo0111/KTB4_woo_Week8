package kr.woo.community.search.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "post_search_outbox_events")
@Getter
@NoArgsConstructor
public class PostSearchOutboxEvent {

    public static final int CURRENT_PAYLOAD_VERSION = 1;

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "post_search_outbox_events_seq_generator"
    )
    @SequenceGenerator(
            name = "post_search_outbox_events_seq_generator",
            sequenceName = "post_search_outbox_events_seq",
            allocationSize = 1
    )
    @Column(name = "outbox_event_id")
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private PostSearchOutboxEventType eventType;

    @Column(name = "payload_version", nullable = false)
    private int payloadVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostSearchOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "last_error", length = 2_000)
    private String lastError;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    private PostSearchOutboxEvent(
            Long aggregateId,
            PostSearchOutboxEventType eventType,
            String payload
    ) {
        if (aggregateId == null || aggregateId <= 0) {
            throw new IllegalArgumentException("aggregateId must be positive");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadVersion = CURRENT_PAYLOAD_VERSION;
        this.payload = payload;
        this.status = PostSearchOutboxStatus.PENDING;
    }

    public static PostSearchOutboxEvent pending(
            Long aggregateId,
            PostSearchOutboxEventType eventType,
            String payload
    ) {
        return new PostSearchOutboxEvent(aggregateId, eventType, payload);
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (availableAt == null) {
            availableAt = now;
        }
    }

    public void markProcessing(String workerId, LocalDateTime claimedAt) {
        if (status != PostSearchOutboxStatus.PENDING) {
            throw new IllegalStateException("Only a pending outbox event can be claimed");
        }
        if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
            throw new IllegalArgumentException("workerId must be between 1 and 100 characters");
        }
        this.status = PostSearchOutboxStatus.PROCESSING;
        this.claimedBy = workerId;
        this.claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
        this.attemptCount++;
        this.lastError = null;
    }

    public boolean markProcessed(String workerId, LocalDateTime processedAt) {
        if (!isClaimedBy(workerId)) {
            return false;
        }
        this.status = PostSearchOutboxStatus.PROCESSED;
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
        clearClaim();
        this.lastError = null;
        return true;
    }

    public boolean markFailedAttempt(
            String workerId,
            LocalDateTime failedAt,
            LocalDateTime nextAvailableAt,
            int maxAttempts,
            String error
    ) {
        if (!isClaimedBy(workerId)) {
            return false;
        }
        validateMaxAttempts(maxAttempts);
        this.lastError = normalizeError(error);
        clearClaim();
        if (attemptCount >= maxAttempts) {
            this.status = PostSearchOutboxStatus.FAILED;
            this.processedAt = Objects.requireNonNull(failedAt, "failedAt");
        } else {
            this.status = PostSearchOutboxStatus.PENDING;
            this.availableAt = Objects.requireNonNull(nextAvailableAt, "nextAvailableAt");
            this.processedAt = null;
        }
        return true;
    }

    public void recoverTimedOutClaim(
            LocalDateTime recoveredAt,
            int maxAttempts
    ) {
        if (status != PostSearchOutboxStatus.PROCESSING) {
            throw new IllegalStateException("Only a processing event can be recovered");
        }
        validateMaxAttempts(maxAttempts);
        this.lastError = "outbox_claim_timed_out";
        clearClaim();
        if (attemptCount >= maxAttempts) {
            this.status = PostSearchOutboxStatus.FAILED;
            this.processedAt = Objects.requireNonNull(recoveredAt, "recoveredAt");
        } else {
            this.status = PostSearchOutboxStatus.PENDING;
            this.availableAt = Objects.requireNonNull(recoveredAt, "recoveredAt");
            this.processedAt = null;
        }
    }

    private boolean isClaimedBy(String workerId) {
        return status == PostSearchOutboxStatus.PROCESSING
                && Objects.equals(claimedBy, workerId);
    }

    private void clearClaim() {
        this.claimedAt = null;
        this.claimedBy = null;
    }

    private void validateMaxAttempts(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
    }

    private String normalizeError(String error) {
        if (error == null || error.isBlank()) {
            return "unknown_outbox_processing_error";
        }
        String normalized = error.strip();
        return normalized.length() <= 2_000
                ? normalized
                : normalized.substring(0, 2_000);
    }
}
