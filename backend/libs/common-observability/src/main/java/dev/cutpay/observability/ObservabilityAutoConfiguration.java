package dev.cutpay.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ObservabilityAutoConfiguration {

    /**
     * Pollers (outbox relay every 0.5 s, job runners every few seconds) would bury real traces under
     * thousands of one-span traces. Set {@code cutpay.observability.trace-scheduled=true} to see them.
     */
    @Bean
    @ConditionalOnProperty(name = "cutpay.observability.trace-scheduled", havingValue = "false", matchIfMissing = true)
    ObservationPredicate skipScheduledTasks() {
        return (name, context) -> !"tasks.scheduled.execution".equals(name);
    }
}
