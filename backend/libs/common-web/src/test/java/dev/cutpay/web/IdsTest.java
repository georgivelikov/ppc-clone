package dev.cutpay.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void hasPrefixAndUlidShape() {
        String id = Ids.generate("mer");
        assertThat(id).matches("mer_[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void sortsByCreationTime() {
        Clock earlier = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        Clock later = Clock.fixed(Instant.parse("2026-01-01T00:00:01Z"), ZoneOffset.UTC);

        assertThat(Ids.generate("pay", earlier)).isLessThan(Ids.generate("pay", later));
    }

    @Test
    void isUniqueWithinTheSameMillisecond() {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var ids = new HashSet<String>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.generate("pay", fixed));
        }
        assertThat(ids).hasSize(10_000);
    }
}
