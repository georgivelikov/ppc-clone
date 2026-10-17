package dev.cutpay.identity.config;

import dev.cutpay.identity.auth.AuthService;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Topics are declared by their producer; broker-side auto creation is disabled. */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic merchantEvents() {
        return TopicBuilder.name(AuthService.MERCHANT_TOPIC).partitions(3).replicas(1).build();
    }
}
