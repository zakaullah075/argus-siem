package com.argus.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * REQUIRES_NEW so the audit record survives a rollback of the operation it
     * describes. A failed privileged action is often the more interesting one,
     * and losing that trail defeats the point of having it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, UUID actorId, String action, String resource) {
        auditLogRepository.save(new AuditLog(tenantId, actorId, action, resource));
    }
}
