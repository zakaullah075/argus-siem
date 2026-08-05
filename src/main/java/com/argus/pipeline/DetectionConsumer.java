package com.argus.pipeline;

import com.argus.common.EventIngested;

import com.argus.ingest.Event;
import com.argus.ingest.EventRepository;
import com.argus.rules.DetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DetectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(DetectionConsumer.class);

    private final EventRepository eventRepository;
    private final DetectionService detectionService;

    public DetectionConsumer(EventRepository eventRepository, DetectionService detectionService) {
        this.eventRepository = eventRepository;
        this.detectionService = detectionService;
    }

    @RabbitListener(queues = RabbitConfig.DETECTION_QUEUE)
    public void onEventIngested(EventIngested message) {
        Event event = eventRepository.findById(message.eventId()).orElse(null);

        if (event == null) {
            // Nothing to evaluate and retrying will not conjure the row, so this
            // is acknowledged rather than sent round the retry loop.
            log.warn("Detection skipped: event {} no longer exists", message.eventId());
            return;
        }

        // Any exception thrown here is retried by the container and eventually
        // dead-lettered. Detection is idempotent in effect — re-evaluating an
        // event folds into the existing alert rather than creating another.
        detectionService.evaluate(event);
    }
}
