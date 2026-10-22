package dev.cutpay.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Observability settings every service shares, added with the lowest precedence so any service
 * (or environment variable) can override them: traces over OTLP/HTTP, Kafka send and receive
 * recorded as spans so a trace follows an event across services, and JSON log files for Loki.
 *
 * <p>Switched off entirely by {@code cutpay.observability.enabled=false}; tests do that.
 */
public class ObservabilityDefaults implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "cutpayObservabilityDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        boolean enabled = environment.getProperty("cutpay.observability.enabled", Boolean.class, true);

        defaults.put("management.tracing.sampling.probability", enabled ? "1.0" : "0.0");
        defaults.put("management.opentelemetry.tracing.export.otlp.endpoint",
                "${CUTPAY_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}");
        defaults.put("management.opentelemetry.tracing.export.otlp.transport", "http");
        defaults.put("management.opentelemetry.tracing.export.otlp.timeout", "2s");
        defaults.put("management.opentelemetry.resource-attributes.service.namespace", "cutpay");
        defaults.put("management.tracing.export.enabled", String.valueOf(enabled));
        defaults.put("management.opentelemetry.tracing.export.otlp.enabled", String.valueOf(enabled));
        defaults.put("management.metrics.tags.application", "${spring.application.name:unknown}");
        defaults.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        defaults.put("management.endpoint.health.probes.enabled", "true");
        // Percentile histograms feed the p50/p95/p99 panels and the SLO alert rules.
        defaults.put("management.metrics.distribution.percentiles-histogram.http.server.requests", "true");
        defaults.put("management.metrics.distribution.slo.http.server.requests", "100ms,300ms,800ms,2s");

        // Fourteen services share one Postgres (max_connections 100 by default): ten idle connections each would use it all.
        defaults.put("spring.datasource.hikari.minimum-idle", "2");
        defaults.put("spring.datasource.hikari.maximum-pool-size", "10");
        defaults.put("spring.datasource.hikari.idle-timeout", "60000");

        // The topics have three partitions; with one consumer thread per listener a service reads them one after the
        // other (docs/perf: the ledger drained ~100 events a second). Events with the same key stay in order.
        defaults.put("spring.kafka.listener.concurrency", "3");

        defaults.put("spring.kafka.template.observation-enabled", "true");
        defaults.put("spring.kafka.listener.observation-enabled", "true");

        if (enabled && environment.getProperty("cutpay.logs.json", Boolean.class, true)) {
            defaults.put("logging.structured.format.file", "logstash");
            defaults.put("logging.file.name",
                    "${CUTPAY_LOG_DIR:${user.dir}/../../../logs}/${spring.application.name:app}.log");
            defaults.put("logging.logback.rollingpolicy.max-file-size", "20MB");
            defaults.put("logging.logback.rollingpolicy.max-history", "3");
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }
}
