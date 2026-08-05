package com.argus.pipeline;

import com.argus.common.EventIngested;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class EventIngestedPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventIngestedPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public EventIngestedPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publishes only after the ingest transaction commits.
     * <p>
     * Publishing inside the transaction would announce an event that a rollback
     * then erased — the consumer would look for a row that never existed. Waiting
     * for AFTER_COMMIT removes that direction of the problem.
     * <p>
     * The opposite direction remains: if the process dies between commit and
     * publish, the event is stored but never evaluated. Closing that properly
     * needs a transactional outbox — write the message to a table in the same
     * transaction and have a relay publish it. Not built; the exposure is a
     * narrow window, and the events are still queryable, just not alerted on.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(EventIngested event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EXCHANGE,
                    RabbitConfig.DETECTION_ROUTING_KEY,
                    event);
        } catch (Exception e) {
            // The event is already committed. Failing the request now would tell
            // the caller to retry something that actually succeeded, producing a
            // duplicate. Log loudly and let the event go un-evaluated instead.
            log.error("Failed to publish ingested event {} for detection", event.eventId(), e);
        }
    }
}
