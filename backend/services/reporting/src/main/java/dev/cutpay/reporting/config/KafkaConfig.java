package dev.cutpay.reporting.config;

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

    @Bean
    NewTopic reportEvents() {
        return TopicBuilder.name(Topics.REPORT).partitions(3).replicas(1).build();
    }

    // The sources are declared here too so the projection can start before the services that produce them.
    @Bean
    NewTopic paymentEvents() {
        return TopicBuilder.name(Topics.PAYMENT).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic payoutEvents() {
        return TopicBuilder.name(Topics.PAYOUT).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic disputeEvents() {
        return TopicBuilder.name(Topics.DISPUTE).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic ledgerEntries() {
        return TopicBuilder.name(Topics.LEDGER_ENTRIES).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic reportingDlt() {
        return TopicBuilder.name(Topics.PAYMENT + ".reporting.dlt").partitions(3).replicas(1).build();
    }

    /** Retries with exponential backoff, then parks the record on a dead-letter topic. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(Topics.PAYMENT + ".reporting.dlt", record.partition()));
        var backoff = new ExponentialBackOffWithMaxRetries(6);
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(10_000);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
