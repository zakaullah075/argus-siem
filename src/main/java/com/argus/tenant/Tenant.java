package com.argus.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String plan;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // JPA requires a no-arg constructor. It stays protected so application code
    // is pushed towards the constructor that produces a valid object.
    protected Tenant() {
    }

    public Tenant(String name, String plan, int rateLimitPerMinute) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.plan = plan;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPlan() {
        return plan;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
