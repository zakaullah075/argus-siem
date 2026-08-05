package com.argus.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

    @Id
    private UUID id;

    /** The thing this message is about — an event id today. */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxMessage() {
    }

    public OutboxMessage(UUID aggregateId, UUID tenantId, String routingKey, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateId = aggregateId;
        this.tenantId = tenantId;
        this.routingKey = routingKey;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Records a failure without marking the row published, so the relay picks it
     * up again. The error is kept because a message that keeps failing needs a
     * reason attached, not just a rising counter.
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error != null && error.length() > 2000
                ? error.substring(0, 2000) : error;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
