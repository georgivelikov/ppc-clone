package dev.cutpay.web;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Opt-in: a clock that tests can advance, plus {@code /admin/clock} to drive it. */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    @ConditionalOnProperty(name = "cutpay.clock.simulated", havingValue = "true")
    SimulatedClock simulatedClock() {
        return new SimulatedClock(Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "cutpay.clock.simulated", havingValue = "true")
    AdminClockController adminClockController(Clock clock) {
        return new AdminClockController(clock);
    }
}
