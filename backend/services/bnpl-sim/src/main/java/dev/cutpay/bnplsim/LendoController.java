package dev.cutpay.bnplsim;

import static dev.cutpay.bnplsim.Providers.LENDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lendo: loans of 6, 12 or 24 months at 9.9% a year for the customer and 2.9% for the merchant, EUR 100 to 10,000.
 * Amounts are decimal strings in euros and applications are form-encoded. The decision is slow and arrives later as a
 * signed, form-encoded callback (it can also be polled). The credit score is worked out from the birthdate, so a test
 * can pick a date that is approved or declined.
 */
@RestController
class LendoController implements Resettable {

    static final BigDecimal MIN = new BigDecimal("100.00");
    static final BigDecimal MAX = new BigDecimal("10000.00");
    static final int FEE_BPS = 290;
    static final double APR = 9.9;
    static final List<Integer> MONTHS = List.of(6, 12, 24);
    static final int MIN_SCORE = 600;

    private static final class Application {
        final String id = "ld_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        volatile String status = "processing";
        int score;
        long amount;
        int months;
        String reference;
        volatile long refunded;
    }

    private final Map<String, Application> applications = new ConcurrentHashMap<>();
    private final Providers providers;
    private final SimProperties properties;
    private final Callbacks callbacks;

    LendoController(Providers providers, SimProperties properties, Callbacks callbacks) {
        this.providers = providers;
        this.properties = properties;
        this.callbacks = callbacks;
    }

    @Override
    public void reset() {
        applications.clear();
    }

    /** The credit score of a birthdate: always the same for the same date, between 400 and 849. */
    static int score(LocalDate birthdate) {
        return 400 + (birthdate.getDayOfMonth() * 13 + birthdate.getMonthValue() * 29 + (birthdate.getYear() % 100) * 7) % 450;
    }

    @GetMapping(value = "/lendo/api/offers", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> offers(@RequestParam String amount, @RequestParam(defaultValue = "EUR") String currency,
                             @RequestParam(required = false) Integer months) {
        providers.enter(LENDO);
        Long minor = parse(amount);
        ResponseEntity<?> refused = refuse(minor, currency);
        if (refused != null) {
            return refused;
        }
        List<Map<String, Object>> offers = new ArrayList<>();
        for (int term : MONTHS) {
            if (months != null && months != term) {
                continue;
            }
            List<Long> payments = Instalments.annuity(minor, term, APR);
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("months", term);
            offer.put("monthly", euros(payments.get(0)));
            offer.put("apr", APR);
            offer.put("total_payable", euros(Instalments.sum(payments)));
            offer.put("merchant_fee", euros(Instalments.fee(minor, FEE_BPS)));
            offers.add(offer);
        }
        return ResponseEntity.ok(Map.of("offers", offers));
    }

    @PostMapping(value = "/lendo/api/applications", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> apply(@RequestParam String amount, @RequestParam(defaultValue = "EUR") String currency,
                            @RequestParam int months, @RequestParam String birthdate, @RequestParam String email,
                            @RequestParam(required = false) String reference,
                            @RequestParam(name = "callback_url", required = false) String callbackUrl) {
        providers.enter(LENDO);
        Long minor = parse(amount);
        ResponseEntity<?> refused = refuse(minor, currency);
        if (refused != null) {
            return refused;
        }
        if (!MONTHS.contains(months)) {
            return error("invalid_term", "Lendo offers 6, 12 or 24 months");
        }
        LocalDate born;
        try {
            born = LocalDate.parse(birthdate);
        } catch (DateTimeParseException e) {
            return error("invalid_birthdate", "Use the format yyyy-MM-dd");
        }
        Application application = new Application();
        application.amount = minor;
        application.months = months;
        application.reference = reference;
        application.score = score(born);
        applications.put(application.id, application);

        Duration delay = Duration.ofMillis(providers.get(LENDO).decisionDelayMs());
        boolean adult = Period.between(born, LocalDate.now()).getYears() >= 18;
        String outcome = adult && application.score >= MIN_SCORE ? "accepted" : "rejected";
        callbacks.postForm(callbackUrl, decisionFields(application, outcome), properties.lendoSecret(), delay);
        resolveLater(application, outcome, delay);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("application_id", application.id, "status", "processing"));
    }

    @GetMapping(value = "/lendo/api/applications/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> get(@PathVariable String id) {
        providers.enter(LENDO);
        Application application = applications.get(id);
        if (application == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown_application"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application_id", application.id);
        body.put("status", application.status);
        if (!"processing".equals(application.status)) {
            body.put("score", application.score);
        }
        body.put("refunded", euros(application.refunded));
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/lendo/api/applications/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> cancel(@PathVariable String id) {
        providers.enter(LENDO);
        Application application = applications.get(id);
        if (application == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown_application"));
        }
        synchronized (application) {
            if ("rejected".equals(application.status) || "canceled".equals(application.status)) {
                return error("not_cancelable", "This application is already closed");
            }
            application.status = "canceled";
        }
        return ResponseEntity.ok(Map.of("application_id", application.id, "status", "canceled"));
    }

    @PostMapping(value = "/lendo/api/applications/{id}/refund", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> refund(@PathVariable String id, @RequestParam String amount) {
        providers.enter(LENDO);
        Application application = applications.get(id);
        if (application == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown_application"));
        }
        Long minor = parse(amount);
        synchronized (application) {
            if (!"accepted".equals(application.status)) {
                return error("not_refundable", "Only an accepted application can be refunded");
            }
            if (minor == null || minor < 1 || application.refunded + minor > application.amount) {
                return error("refund_too_large", "More than what was financed");
            }
            application.refunded += minor;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("application_id", application.id, "refunded", euros(application.refunded)));
    }

    private void resolveLater(Application application, String outcome, Duration delay) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (application) {
                if ("processing".equals(application.status)) {
                    application.status = outcome;
                }
            }
        });
    }

    private Map<String, String> decisionFields(Application application, String outcome) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("application_id", application.id);
        fields.put("outcome", outcome);
        fields.put("score", String.valueOf(application.score));
        fields.put("reference", application.reference);
        return fields;
    }

    private static ResponseEntity<?> refuse(Long minor, String currency) {
        if (!"EUR".equalsIgnoreCase(currency)) {
            return error("unsupported_currency", "Lendo works in EUR only");
        }
        if (minor == null) {
            return error("invalid_amount", "Send the amount as a decimal such as 250.00");
        }
        if (minor < MIN.movePointRight(2).longValue() || minor > MAX.movePointRight(2).longValue()) {
            return error("amount_out_of_range", "Lendo finances 100.00 to 10000.00 EUR");
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> error(String code, String message) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", code, "detail", message));
    }

    /** "250.00" as 25000; null if it is not a decimal with at most two places. */
    static Long parse(String amount) {
        try {
            BigDecimal value = new BigDecimal(amount);
            if (value.scale() > 2) {
                return null;
            }
            return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    static String euros(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
