package com.argus.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "argus.events";
    public static final String DETECTION_QUEUE = "argus.detection";
    public static final String DETECTION_ROUTING_KEY = "event.ingested";

    static final String DEAD_LETTER_EXCHANGE = "argus.events.dlx";
    static final String DEAD_LETTER_QUEUE = "argus.detection.dlq";

    /**
     * JSON on the wire. The default converter uses Java serialization, which
     * cannot handle a record and would tie the message format to the exact class
     * on both sides — a consumer could not be rewritten in another language, and
     * a field addition would break in-flight messages.
     */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    DirectExchange eventsExchange() {
        return new DirectExchange(EXCHANGE);
    }

    /**
     * Messages that exhaust their retries are routed to the dead letter queue
     * rather than discarded or redelivered forever. A poison message that loops
     * indefinitely blocks the queue behind it and looks identical to an outage.
     */
    @Bean
    Queue detectionQueue() {
        return QueueBuilder.durable(DETECTION_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DETECTION_ROUTING_KEY)
                .build();
    }

    @Bean
    Binding detectionBinding(Queue detectionQueue, DirectExchange eventsExchange) {
        return BindingBuilder.bind(detectionQueue).to(eventsExchange).with(DETECTION_ROUTING_KEY);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DETECTION_ROUTING_KEY);
    }
}
