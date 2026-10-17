package dev.cutpay.ledger.config;

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
    NewTopic ledgerEntries() {
        return TopicBuilder.name(Topics.LEDGER_ENTRIES).partitions(3).replicas(1).build();
    }

    // Dead-letter topics for the topics this service consumes. The recoverer sends a record to the same
    // partition number, so these need at least as many partitions as the source topics (3).
    @Bean
    NewTopic merchantDlt() {
        return TopicBuilder.name(Topics.MERCHANT + ".dlt").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentDlt() {
        return TopicBuilder.name(Topics.PAYMENT + ".dlt").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic refundDlt() {
        return TopicBuilder.name(Topics.REFUND + ".dlt").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic payoutDlt() {
        return TopicBuilder.name(Topics.PAYOUT + ".dlt").partitions(3).replicas(1).build();
    }

    /**
     * Retries a failing record with exponential backoff, then parks it on {@code <topic>.dlt} so one bad
     * event cannot block the partition. Retrying matters here: a refund can arrive before the payment it
     * refunds (they travel on different topics), and succeeds on a later attempt. Spring Boot applies this
     * bean to every listener container.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
        var backoff = new ExponentialBackOffWithMaxRetries(6);
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(10_000);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        // A message that is not valid JSON will never become valid: do not wait for it.
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
