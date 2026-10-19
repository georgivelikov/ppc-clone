package dev.cutpay.notifications.config;

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
    NewTopic notificationEvents() {
        return TopicBuilder.name(Topics.NOTIFICATION).partitions(3).replicas(1).build();
    }

    // Declared here too so listeners can start before the producing service has: creating a topic twice is harmless.
    @Bean
    NewTopic disputeEvents() {
        return TopicBuilder.name(Topics.DISPUTE).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic onboardingEvents() {
        return TopicBuilder.name(Topics.ONBOARDING).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic reportEvents() {
        return TopicBuilder.name(Topics.REPORT).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic notificationEventsDlt() {
        return TopicBuilder.name(Topics.NOTIFICATION + ".dlt").partitions(3).replicas(1).build();
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
