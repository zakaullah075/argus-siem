package com.argus.alerts;

import com.argus.alerts.dto.AlertResponse;
import org.springframework.stereotype.Component;

@Component
public class AlertMapper {

    public AlertResponse toResponse(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getRuleId(),
                alert.getStatus(),
                alert.getSeverity(),
                alert.getDedupeKey(),
                alert.getOccurrenceCount(),
                alert.getFirstSeenAt(),
                alert.getLastSeenAt()
        );
    }
}
