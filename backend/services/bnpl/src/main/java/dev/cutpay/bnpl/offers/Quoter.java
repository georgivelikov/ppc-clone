package dev.cutpay.bnpl.offers;

import dev.cutpay.bnpl.adapter.Adapters;
import dev.cutpay.bnpl.adapter.BnplProviderAdapter;
import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.QuoteRequest;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks every provider for its offers at once and waits no longer than the deadline in total. A provider that is slow,
 * down or refuses is simply missing from the answer, with a sentence saying why: a partial list is better than making
 * the customer wait for the slowest.
 */
@Component
public class Quoter {

    private static final Logger log = LoggerFactory.getLogger(Quoter.class);

    /** Offers by provider code for those that answered, and for the others a sentence about what went wrong. */
    public record Quotes(Map<String, List<Offer>> offers, Map<String, String> failures) {
    }

    private final Adapters adapters;
    private final Duration deadline;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public Quoter(Adapters adapters, BnplProperties properties) {
        this.adapters = adapters;
        this.deadline = properties.offersDeadline();
    }

    @PreDestroy
    void stop() {
        executor.close();
    }

    public Quotes quote(List<ProviderInfo> providers, QuoteRequest request) {
        Map<String, Future<List<Offer>>> pending = new LinkedHashMap<>();
        for (ProviderInfo provider : providers) {
            adapters.find(provider.code()).ifPresent(adapter ->
                    pending.put(provider.code(), executor.submit(() -> adapter.quote(request))));
        }
        Map<String, List<Offer>> offers = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        long end = System.nanoTime() + deadline.toNanos();
        pending.forEach((code, future) -> {
            try {
                offers.put(code, future.get(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                failures.put(code, "did not answer in time");
            } catch (ExecutionException e) {
                failures.put(code, explain(code, e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                failures.put(code, "was not asked");
            }
        });
        return new Quotes(offers, failures);
    }

    private static String explain(String code, Throwable cause) {
        if (cause instanceof ProviderException provider) {
            log.info("{}", provider.getMessage());
            return switch (provider.kind()) {
                case TIMEOUT -> "did not answer in time";
                case UNAVAILABLE -> "is not responding";
                case REFUSED -> "declined to quote";
                case INVALID_RESPONSE -> "sent an answer that could not be read";
            };
        }
        log.warn("Unexpected failure quoting {}", code, cause);
        return "failed unexpectedly";
    }
}
