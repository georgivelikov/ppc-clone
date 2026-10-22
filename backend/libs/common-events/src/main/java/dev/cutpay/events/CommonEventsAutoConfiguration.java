package dev.cutpay.events;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, KafkaAutoConfiguration.class})
@EnableScheduling
@ConditionalOnBean(JdbcClient.class)
public class CommonEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TraceLink traceLink(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new TraceLink(tracer, propagator);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper mapper, TraceLink trace,
                              ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        return new OutboxWriter(jdbc, mapper, trace, meters.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    InboxGuard inboxGuard(JdbcClient jdbc) {
        return new InboxGuard(jdbc);
    }

    /** Needs the {@code shedlock} table in services that actually run locked jobs. */
    @Bean
    @ConditionalOnMissingBean
    JobLock jobLock(JdbcClient jdbc) {
        return new JobLock(jdbc);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                           TraceLink trace) {
        return new OutboxRelay(jdbc, kafka, tx, trace);
    }
}
