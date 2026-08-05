package com.argus.alerts;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    Optional<Alert> findByTenantIdAndDedupeKeyAndStatusNot(UUID tenantId,
                                                          String dedupeKey,
                                                          AlertStatus status);

    Page<Alert> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Alert> findByIdAndTenantId(UUID id, UUID tenantId);
}
