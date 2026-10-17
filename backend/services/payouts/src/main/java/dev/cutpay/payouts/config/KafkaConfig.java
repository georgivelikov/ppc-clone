package dev.cutpay.payouts.config;

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

    /** Topics are declared by their producer; broker-side auto creation is disabled. */
    @Bean
    NewTopic payoutEvents() {
        return TopicBuilder.name(Topics.PAYOUT).partitions(3).replicas(1).build();
    }

    // Dead-letter topics for what this service consumes (same partition count as the source topics).
    @Bean
    NewTopic merchantDlt() {
        return TopicBuilder.name(Topics.MERCHANT + ".dlt").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic ledgerEntriesDlt() {
        return TopicBuilder.name(Topics.LEDGER_ENTRIES + ".dlt").partitions(3).replicas(1).build();
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
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
