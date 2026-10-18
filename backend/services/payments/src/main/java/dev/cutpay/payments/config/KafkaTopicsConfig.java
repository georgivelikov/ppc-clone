package dev.cutpay.payments.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Topics are declared by their producer; broker-side auto creation is disabled. */
@Configuration
public class KafkaTopicsConfig {

    public static final String PAYMENT_TOPIC = "payment.events";
    public static final String REFUND_TOPIC = "refund.events";
    public static final String CUSTOMER_TOPIC = "customer.events";

    @Bean
    NewTopic paymentEvents() {
        return TopicBuilder.name(PAYMENT_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic refundEvents() {
        return TopicBuilder.name(REFUND_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic customerEvents() {
        return TopicBuilder.name(CUSTOMER_TOPIC).partitions(3).replicas(1).build();
    }
}
