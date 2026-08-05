package com.argus.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes a message in the caller's transaction, which is the entire point.
 * <p>
 * MANDATORY rather than REQUIRED: if there is no surrounding transaction the
 * outbox row would commit on its own, and the atomicity this exists to provide
 * would be silently absent. Failing loudly is better than appearing to work.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID aggregateId, UUID tenantId, String routingKey, Object payload) {
        try {
            outboxRepository.save(new OutboxMessage(
                    aggregateId, tenantId, routingKey, objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            // Serialisation cannot fail for the records this handles, so if it
            // does the message is malformed and the whole operation should fail
            // rather than commit an event nobody will ever evaluate.
            throw new IllegalStateException("Could not serialise outbox payload", e);
        }
    }
}
