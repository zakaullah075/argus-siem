package com.argus.ingest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Page<Event> findByTenantId(UUID tenantId, Pageable pageable);

    /**
     * Counts events matching a rule's conditions inside its window.
     * <p>
     * A null source or event type means "any", handled here rather than by
     * building the query dynamically — the conditions are few and fixed, so a
     * Criteria API builder would cost more in complexity than it saves.
     * <p>
     * Severities arrive as an explicit set because the column stores enum names,
     * so a relational comparison would order them alphabetically, not by rank.
     */
    @Query("""
            select count(e) from Event e
            where e.tenantId = :tenantId
              and (:source is null or e.source = :source)
              and (:eventType is null or e.eventType = :eventType)
              and e.severity in :severities
              and e.occurredAt >= :since
              and (:actor is null or e.actor = :actor)
            """)
    long countMatching(@Param("tenantId") UUID tenantId,
                       @Param("source") String source,
                       @Param("eventType") String eventType,
                       @Param("severities") Collection<Severity> severities,
                       @Param("since") Instant since,
                       @Param("actor") String actor);
}
