package com.argus.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Claims a batch for publishing.
     * <p>
     * PESSIMISTIC_WRITE with SKIP LOCKED so two instances of the relay can run
     * without publishing the same message twice: each skips rows the other has
     * already claimed rather than blocking behind them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select m from OutboxMessage m where m.publishedAt is null order by m.createdAt asc")
    List<OutboxMessage> claimUnpublished(Pageable pageable);

    long countByPublishedAtIsNull();

    void deleteByPublishedAtBefore(Instant cutoff);
}
