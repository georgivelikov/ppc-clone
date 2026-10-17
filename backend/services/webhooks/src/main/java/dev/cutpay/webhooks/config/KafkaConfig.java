package dev.cutpay.webhooks.config;

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

@Configuration
public class KafkaConfig {

    // Dead-letter topics for what this service consumes (same partition count as the source topics).
    // The source topics themselves are declared by their producers.
    @Bean
    NewTopic paymentDlt() {
        return TopicBuilder.name(Topics.PAYMENT + ".webhooks.dlt").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic refundDlt() {
        return TopicBuilder.name(Topics.REFUND + ".webhooks.dlt").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic payoutDlt() {
        return TopicBuilder.name(Topics.PAYOUT + ".webhooks.dlt").partitions(3).replicas(1).build();
    }

    // Declared by the gateway too (the producer); declaring it here as well makes start-up order irrelevant.
    @Bean
    NewTopic apiRequests() {
        return TopicBuilder.name(Topics.API_REQUESTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic apiRequestsDlt() {
        return TopicBuilder.name(Topics.API_REQUESTS + ".webhooks.dlt").partitions(3).replicas(1).build();
    }

    /** Retries with exponential backoff, then parks the record on {@code <topic>.webhooks.dlt}. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".webhooks.dlt", record.partition()));
        var backoff = new ExponentialBackOffWithMaxRetries(6);
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(10_000);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
