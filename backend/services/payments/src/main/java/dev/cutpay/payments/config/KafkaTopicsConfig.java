package dev.cutpay.payments.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

/** Topics are declared by their producer; broker-side auto creation is disabled. */
@Configuration
public class KafkaTopicsConfig {

    public static final String PAYMENT_TOPIC = "payment.events";
    public static final String REFUND_TOPIC = "refund.events";
    public static final String CUSTOMER_TOPIC = "customer.events";
    public static final String DISPUTE_TOPIC = "dispute.events";
    /** Produced by the bnpl service; payments listens to it. */
    public static final String BNPL_TOPIC = "bnpl.events";

    @Bean
    NewTopic paymentEvents() {
        return TopicBuilder.name(PAYMENT_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic refundEvents() {
        return TopicBuilder.name(REFUND_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic disputeEvents() {
        return TopicBuilder.name(DISPUTE_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic customerEvents() {
        return TopicBuilder.name(CUSTOMER_TOPIC).partitions(3).replicas(1).build();
    }

    /** Produced by the bnpl service; declared here too so the topic exists whichever service starts first. */
    @Bean
    NewTopic bnplEvents() {
        return TopicBuilder.name(BNPL_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDeadLetters() {
        return TopicBuilder.name(PAYMENT_TOPIC + ".dlt").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic refundEventsDeadLetters() {
        return TopicBuilder.name(REFUND_TOPIC + ".dlt").partitions(3).replicas(1).build();
    }

    /** Where records that keep failing end up, so a bad BNPL event does not block the ones behind it or vanish. */
    @Bean
    NewTopic bnplEventsDeadLetters() {
        return TopicBuilder.name(BNPL_TOPIC + ".dlt").partitions(3).replicas(1).build();
    }

    /** Retries with exponential backoff, then parks the record on {@code <topic>.dlt}. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
        var backoff = new ExponentialBackOffWithMaxRetries(6);
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(10_000);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
