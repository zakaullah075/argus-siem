package com.argus.alerts;

import com.argus.ingest.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert")
public class Alert {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected Alert() {
    }

    public Alert(UUID tenantId, UUID ruleId, Severity severity, String dedupeKey) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.ruleId = ruleId;
        this.status = AlertStatus.OPEN;
        this.severity = severity;
        this.dedupeKey = dedupeKey;
        this.occurrenceCount = 1;
        this.firstSeenAt = Instant.now();
        this.lastSeenAt = this.firstSeenAt;
    }

    /**
     * Folds a repeat occurrence into this alert instead of creating another one.
     */
    public void recordOccurrence() {
        this.occurrenceCount++;
        this.lastSeenAt = Instant.now();
    }

    public void acknowledge() {
        this.status = AlertStatus.ACKNOWLEDGED;
    }

    public void resolve() {
        this.status = AlertStatus.RESOLVED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
