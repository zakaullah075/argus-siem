package com.argus.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false)
    private String action;

    private String resource;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected AuditLog() {
    }

    public AuditLog(UUID tenantId, UUID actorId, String action, String resource) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.action = action;
        this.resource = resource;
        this.at = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getResource() {
        return resource;
    }

    public Instant getAt() {
        return at;
    }
}
