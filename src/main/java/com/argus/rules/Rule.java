package com.argus.rules;

import com.argus.common.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rule")
public class Rule {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean enabled;

    // A null match column means "any value". Keeping them as plain columns rather
    // than a serialised expression means the matching can move into SQL later
    // without a migration of the rule data itself.
    @Column(name = "match_source")
    private String matchSource;

    @Column(name = "match_event_type")
    private String matchEventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "min_severity")
    private Severity minSeverity;

    @Column(name = "threshold_count", nullable = false)
    private int thresholdCount;

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_severity", nullable = false)
    private Severity alertSeverity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Rule() {
    }

    public Rule(UUID tenantId,
                String name,
                String matchSource,
                String matchEventType,
                Severity minSeverity,
                int thresholdCount,
                int windowSeconds,
                Severity alertSeverity) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.enabled = true;
        this.matchSource = matchSource;
        this.matchEventType = matchEventType;
        this.minSeverity = minSeverity;
        this.thresholdCount = thresholdCount;
        this.windowSeconds = windowSeconds;
        this.alertSeverity = alertSeverity;
        this.createdAt = Instant.now();
    }

    /**
     * Whether a single event satisfies this rule's conditions. Threshold and
     * window are evaluated separately, because they need the event history.
     */
    public boolean matches(String source, String eventType, Severity severity) {
        if (matchSource != null && !matchSource.equals(source)) {
            return false;
        }
        if (matchEventType != null && !matchEventType.equals(eventType)) {
            return false;
        }
        return minSeverity == null || severity.ordinal() >= minSeverity.ordinal();
    }

    public void disable() {
        this.enabled = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getMatchSource() {
        return matchSource;
    }

    public String getMatchEventType() {
        return matchEventType;
    }

    public Severity getMinSeverity() {
        return minSeverity;
    }

    public int getThresholdCount() {
        return thresholdCount;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public Severity getAlertSeverity() {
        return alertSeverity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
