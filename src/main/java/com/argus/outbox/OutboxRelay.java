package com.argus.outbox;

import com.argus.common.EventIngested;
import com.argus.pipeline.RabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Publishes whatever the outbox holds, then marks it published.
 * <p>
 * Delivery is at-least-once: a crash after the broker accepts a message but
 * before the row is marked will republish it. That is the correct direction to
 * fail — detection folds a repeat into the existing alert, so a duplicate costs
 * nothing, while a lost message means an attack goes unnoticed.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(OutboxRepository outboxRepository,
                       RabbitTemplate rabbitTemplate,
                       ObjectMapper objectMapper,
                       MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.published = meterRegistry.counter("argus.outbox.published");
        this.failed = meterRegistry.counter("argus.outbox.failed");
    }

    @Scheduled(fixedDelayString = "${argus.outbox.poll-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> batch = outboxRepository.claimUnpublished(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxMessage message : batch) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitConfig.EXCHANGE,
                        message.getRoutingKey(),
                        objectMapper.readValue(message.getPayload(), EventIngested.class));

                message.markPublished();
                published.increment();

            } catch (Exception e) {
                // Left unpublished on purpose, so the next run retries it. The
                // batch continues: one poisonous message must not stall every
                // other tenant's events behind it.
                message.markFailed(e.getMessage());
                failed.increment();
                log.warn("Outbox publish failed for {} (attempt {})",
                        message.getId(), message.getAttempts(), e);
            }
        }
    }

    /**
     * Published rows are history nobody reads. Without this the table grows
     * forever and the partial index scan slowly stops being cheap.
     */
    @Scheduled(cron = "${argus.outbox.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purgePublished() {
        outboxRepository.deleteByPublishedAtBefore(Instant.now().minus(7, ChronoUnit.DAYS));
    }
}
