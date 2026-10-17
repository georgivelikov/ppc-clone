package dev.cutpay.web;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controls for the {@link SimulatedClock}. Not routed by the gateway; only reachable on a
 * service's own port.
 */
@RestController
class AdminClockController {

    private final SimulatedClock clock;

    AdminClockController(Clock clock) {
        if (!(clock instanceof SimulatedClock simulated)) {
            throw new IllegalStateException("The admin clock endpoints need cutpay.clock.simulated=true");
        }
        this.clock = simulated;
    }

    @GetMapping("/admin/clock")
    Map<String, Object> now() {
        return view();
    }

    @PostMapping("/admin/clock/advance")
    Map<String, Object> advance(@RequestParam(defaultValue = "0") long days,
                                @RequestParam(defaultValue = "0") long hours,
                                @RequestParam(defaultValue = "0") long minutes) {
        if (days < 0 || hours < 0 || minutes < 0) {
            throw ApiException.invalidRequest("invalid_duration", "The clock can only move forward", "days");
        }
        clock.advance(Duration.ofDays(days).plusHours(hours).plusMinutes(minutes));
        return view();
    }

    private Map<String, Object> view() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("now", clock.instant().toString());
        body.put("offset_seconds", clock.offset().toSeconds());
        return body;
    }
}
