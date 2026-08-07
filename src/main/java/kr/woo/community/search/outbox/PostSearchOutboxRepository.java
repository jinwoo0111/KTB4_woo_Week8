package kr.woo.community.search.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;

import java.util.List;

public interface PostSearchOutboxRepository
        extends JpaRepository<PostSearchOutboxEvent, Long> {

    List<PostSearchOutboxEvent> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT event
        FROM PostSearchOutboxEvent event
        WHERE event.status = :status
        AND event.availableAt <= :now
        AND NOT EXISTS (
            SELECT older.id
            FROM PostSearchOutboxEvent older
            WHERE older.aggregateId = event.aggregateId
            AND older.id < event.id
            AND older.status IN (
                kr.woo.community.search.outbox.PostSearchOutboxStatus.PENDING,
                kr.woo.community.search.outbox.PostSearchOutboxStatus.PROCESSING
            )
        )
        ORDER BY event.id ASC
    """)
    List<PostSearchOutboxEvent> findClaimableForUpdateSkipLocked(
            @Param("status") PostSearchOutboxStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT event
        FROM PostSearchOutboxEvent event
        WHERE event.status = :status
        AND event.claimedAt <= :claimDeadline
        ORDER BY event.id ASC
    """)
    List<PostSearchOutboxEvent> findTimedOutForUpdateSkipLocked(
            @Param("status") PostSearchOutboxStatus status,
            @Param("claimDeadline") LocalDateTime claimDeadline,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT event
        FROM PostSearchOutboxEvent event
        WHERE event.id = :eventId
    """)
    java.util.Optional<PostSearchOutboxEvent> findByIdForUpdate(
            @Param("eventId") Long eventId
    );
}
