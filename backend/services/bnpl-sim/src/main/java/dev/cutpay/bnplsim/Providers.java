package dev.cutpay.bnplsim;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * What can be changed about each provider while the simulator runs: an outage switch, extra latency, how often it
 * hangs, and how long it takes to decide when it decides on its own. {@link #enter} is called first by every provider
 * endpoint, so the switches behave the same everywhere.
 */
@Component
public class Providers {

    public static final String SPLITNOW = "splitnow";
    public static final String LENDO = "lendo";
    public static final String KREDITKO = "kreditko";
    public static final String PAYLATEREU = "paylatereu";

    /** The settings of one provider. Fields are replaced as a whole, so readers never see a half-applied change. */
    public record Settings(boolean outage, long latencyMs, double timeoutRate, long decisionDelayMs) {
    }

    private final Map<String, Settings> settings = new LinkedHashMap<>();
    private final Map<String, Settings> defaults = new LinkedHashMap<>();
    private final SimProperties properties;
    private final RandomGenerator random;

    @org.springframework.beans.factory.annotation.Autowired
    Providers(SimProperties properties) {
        this(properties, RandomGenerator.getDefault());
    }

    Providers(SimProperties properties, RandomGenerator random) {
        this.properties = properties;
        this.random = random;
        defaults.put(SPLITNOW, new Settings(false, properties.splitnowLatency().toMillis(), 0, 0));
        defaults.put(LENDO, new Settings(false, 0, 0, properties.lendoDecisionDelay().toMillis()));
        defaults.put(KREDITKO, new Settings(false, 0, 0, properties.kreditkoReviewDelay().toMillis()));
        defaults.put(PAYLATEREU, new Settings(false, 0, properties.paylatereuTimeoutRate(), 0));
        reset();
    }

    public synchronized void reset() {
        settings.clear();
        settings.putAll(defaults);
    }

    public synchronized Map<String, Settings> all() {
        return new LinkedHashMap<>(settings);
    }

    public synchronized Settings get(String code) {
        return Optional.ofNullable(settings.get(code))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such provider"));
    }

    public synchronized Settings update(String code, Boolean outage, Long latencyMs, Double timeoutRate, Long decisionDelayMs) {
        Settings current = get(code);
        Settings next = new Settings(outage != null ? outage : current.outage(),
                latencyMs != null ? Math.max(0, latencyMs) : current.latencyMs(),
                timeoutRate != null ? Math.min(1, Math.max(0, timeoutRate)) : current.timeoutRate(),
                decisionDelayMs != null ? Math.max(0, decisionDelayMs) : current.decisionDelayMs());
        settings.put(code, next);
        return next;
    }

    public Duration timeoutHang() {
        return properties.timeoutHang();
    }

    /**
     * The first thing every provider call does: fail with a 503 during an outage, hang and then fail with a 504 for
     * the share of calls that time out, and otherwise wait for the configured latency.
     */
    public Settings enter(String code) {
        Settings now = get(code);
        if (now.outage()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, code + " is down");
        }
        if (now.timeoutRate() > 0 && random.nextDouble() < now.timeoutRate()) {
            sleep(properties.timeoutHang().toMillis());
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, code + " did not answer in time");
        }
        sleep(now.latencyMs());
        return now;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
