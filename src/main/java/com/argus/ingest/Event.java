package com.argus.ingest;

import com.argus.common.Severity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event")
public class Event {

    // Supplied by the caller, not generated here. A retrying agent resends the
    // same id, so the primary key itself becomes the idempotency guarantee —
    // a duplicate delivery collides instead of creating a second row.
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String source;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    // STRING, not ORDINAL. Ordinal stores the enum's position, so reordering the
    // enum silently rewrites the meaning of every existing row.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    private String actor;

    private String target;

    // The original payload is kept verbatim. Normalisation is lossy, and when a
    // detection rule misfires the raw event is the only way to find out why.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false)
    private String rawPayload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected Event() {
    }

    public Event(UUID id,
                 UUID tenantId,
                 String source,
                 String eventType,
                 Severity severity,
                 String actor,
                 String target,
                 String rawPayload,
                 Instant occurredAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.source = source;
        this.eventType = eventType;
        this.severity = severity;
        this.actor = actor;
        this.target = target;
        this.rawPayload = rawPayload;
        this.occurredAt = occurredAt;
        this.ingestedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSource() {
        return source;
    }

    public String getEventType() {
        return eventType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getActor() {
        return actor;
    }

    public String getTarget() {
        return target;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }
}
