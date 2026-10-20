package dev.cutpay.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ObservabilityDefaultsTest {

    private final ObservabilityDefaults defaults = new ObservabilityDefaults();

    @Test
    void tracesEverythingAndObservesKafkaByDefault() {
        var env = new MockEnvironment();
        defaults.postProcessEnvironment(env, null);

        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        assertThat(env.getProperty("spring.kafka.listener.observation-enabled")).isEqualTo("true");
        assertThat(env.getProperty("logging.structured.format.file")).isEqualTo("logstash");
    }

    @Test
    void canBeSwitchedOff() {
        var env = new MockEnvironment().withProperty("cutpay.observability.enabled", "false");
        defaults.postProcessEnvironment(env, null);

        assertThat(env.getProperty("management.tracing.export.enabled")).isEqualTo("false");
        assertThat(env.getProperty("logging.file.name")).isNull();
    }

    @Test
    void servicePropertiesWin() {
        var env = new MockEnvironment().withProperty("management.tracing.sampling.probability", "0.1");
        defaults.postProcessEnvironment(env, null);

        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
    }
}
