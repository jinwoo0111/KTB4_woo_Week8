package kr.woo.community.search.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class PostSearchOutboxStateService {

    private final PostSearchOutboxRepository outboxRepository;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public PostSearchOutboxStateService(
            PostSearchOutboxRepository outboxRepository,
            @Value("${app.search.outbox.max-attempts:5}") int maxAttempts,
            @Value("${app.search.outbox.base-backoff:PT1S}") Duration baseBackoff,
            @Value("${app.search.outbox.max-backoff:PT1M}") Duration maxBackoff
    ) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (baseBackoff == null || baseBackoff.isZero() || baseBackoff.isNegative()) {
            throw new IllegalArgumentException("baseBackoff must be positive");
        }
        if (maxBackoff == null || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be at least baseBackoff");
        }
        this.outboxRepository = outboxRepository;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessed(long eventId, String workerId) {
        return outboxRepository.findByIdForUpdate(eventId)
                .map(event -> event.markProcessed(workerId, LocalDateTime.now()))
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(long eventId, String workerId, Throwable failure) {
        return outboxRepository.findByIdForUpdate(eventId)
                .map(event -> {
                    LocalDateTime now = LocalDateTime.now();
                    Duration backoff = calculateBackoff(event.getAttemptCount());
                    return event.markFailedAttempt(
                            workerId,
                            now,
                            now.plus(backoff),
                            maxAttempts,
                            describe(failure)
                    );
                })
                .orElse(false);
    }

    private Duration calculateBackoff(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
        long multiplier = 1L << exponent;
        Duration calculated;
        try {
            calculated = baseBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException e) {
            return maxBackoff;
        }
        return calculated.compareTo(maxBackoff) > 0 ? maxBackoff : calculated;
    }

    private String describe(Throwable failure) {
        if (failure == null) {
            return "unknown_outbox_processing_error";
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
