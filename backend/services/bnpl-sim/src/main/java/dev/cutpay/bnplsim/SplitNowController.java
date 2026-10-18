package dev.cutpay.bnplsim;

import static dev.cutpay.bnplsim.Providers.SPLITNOW;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SplitNow: pay in 3 or 4 instalments, no interest for the customer, 3.5% for the merchant, EUR 50 to 1,500. A plain
 * synchronous JSON API in cents: the answer to an application is in the response. Approves anyone whose email is not
 * on its blocklist.
 */
@RestController
class SplitNowController implements Resettable {

    static final long MIN = 5_000;
    static final long MAX = 150_000;
    static final int FEE_BPS = 350;
    static final List<Integer> PLANS = List.of(3, 4);

    record QuoteRequest(@NotNull Long amountCents, @NotBlank String currency) {
    }

    record Plan(int instalments, List<Long> instalmentCents, double interestPct, double merchantFeePct, long merchantFeeCents) {
    }

    record QuoteResponse(String currency, List<Plan> plans) {
    }

    record Customer(@NotBlank String email, String name) {
    }

    record CheckoutRequest(@NotNull Long amountCents, @NotBlank String currency, @NotNull Integer instalments,
                           @Valid @NotNull Customer customer, String reference) {
    }

    record ScheduleItem(int number, int dueInDays, long amountCents) {
    }

    record CheckoutResponse(String id, String state, String declineReason, long amountCents, String currency,
                            int instalments, long merchantFeeCents, List<ScheduleItem> schedule, String reference,
                            long refundedCents) {
    }

    record RefundRequest(@NotNull Long amountCents) {
    }

    record Failure(Map<String, String> error) {
    }

    private static final class Checkout {
        final String id = "sn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String state;
        String declineReason;
        long amount;
        String currency;
        int instalments;
        long fee;
        List<ScheduleItem> schedule;
        String reference;
        long refunded;

        CheckoutResponse view() {
            return new CheckoutResponse(id, state, declineReason, amount, currency, instalments, fee, schedule, reference, refunded);
        }
    }

    private final Map<String, Checkout> checkouts = new ConcurrentHashMap<>();
    private final Providers providers;
    private final SimProperties properties;

    SplitNowController(Providers providers, SimProperties properties) {
        this.providers = providers;
        this.properties = properties;
    }

    @Override
    public void reset() {
        checkouts.clear();
    }

    @PostMapping("/splitnow/v1/quotes")
    ResponseEntity<?> quote(@Valid @RequestBody QuoteRequest request) {
        providers.enter(SPLITNOW);
        ResponseEntity<Failure> refused = refuse(request.amountCents(), request.currency());
        if (refused != null) {
            return refused;
        }
        List<Plan> plans = new ArrayList<>();
        for (int count : PLANS) {
            plans.add(new Plan(count, Instalments.equal(request.amountCents(), count), 0, 3.5,
                    Instalments.fee(request.amountCents(), FEE_BPS)));
        }
        return ResponseEntity.ok(new QuoteResponse("EUR", plans));
    }

    @PostMapping("/splitnow/v1/checkouts")
    ResponseEntity<?> create(@Valid @RequestBody CheckoutRequest request) {
        providers.enter(SPLITNOW);
        ResponseEntity<Failure> refused = refuse(request.amountCents(), request.currency());
        if (refused != null) {
            return refused;
        }
        if (!PLANS.contains(request.instalments())) {
            return failure("unsupported_plan", "SplitNow offers 3 or 4 instalments");
        }
        Checkout checkout = new Checkout();
        checkout.amount = request.amountCents();
        checkout.currency = "EUR";
        checkout.instalments = request.instalments();
        checkout.fee = Instalments.fee(request.amountCents(), FEE_BPS);
        checkout.reference = request.reference();
        boolean blocked = properties.splitnowBlocklist().stream()
                .anyMatch(blockedEmail -> blockedEmail.equalsIgnoreCase(request.customer().email()));
        checkout.state = blocked ? "declined" : "approved";
        checkout.declineReason = blocked ? "risk_check_failed" : null;
        List<Long> parts = Instalments.equal(request.amountCents(), request.instalments());
        checkout.schedule = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            checkout.schedule.add(new ScheduleItem(i + 1, i * 14, parts.get(i)));
        }
        checkouts.put(checkout.id, checkout);
        return ResponseEntity.status(HttpStatus.CREATED).body(checkout.view());
    }

    @GetMapping("/splitnow/v1/checkouts/{id}")
    ResponseEntity<?> get(@PathVariable String id) {
        providers.enter(SPLITNOW);
        Checkout checkout = checkouts.get(id);
        return checkout == null ? notFound() : ResponseEntity.ok(checkout.view());
    }

    @PostMapping("/splitnow/v1/checkouts/{id}/cancel")
    ResponseEntity<?> cancel(@PathVariable String id) {
        providers.enter(SPLITNOW);
        Checkout checkout = checkouts.get(id);
        if (checkout == null) {
            return notFound();
        }
        synchronized (checkout) {
            if (!"approved".equals(checkout.state) || checkout.refunded > 0) {
                return failure("not_cancelable", "Only an approved checkout without refunds can be canceled");
            }
            checkout.state = "canceled";
        }
        return ResponseEntity.ok(checkout.view());
    }

    @PostMapping("/splitnow/v1/checkouts/{id}/refunds")
    ResponseEntity<?> refund(@PathVariable String id, @Valid @RequestBody RefundRequest request) {
        providers.enter(SPLITNOW);
        Checkout checkout = checkouts.get(id);
        if (checkout == null) {
            return notFound();
        }
        synchronized (checkout) {
            if (!"approved".equals(checkout.state)) {
                return failure("not_refundable", "Only an approved checkout can be refunded");
            }
            if (request.amountCents() < 1 || checkout.refunded + request.amountCents() > checkout.amount) {
                return failure("refund_too_large", "More than what was paid");
            }
            checkout.refunded += request.amountCents();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(checkout.view());
    }

    private static ResponseEntity<Failure> refuse(long amount, String currency) {
        if (!"EUR".equalsIgnoreCase(currency)) {
            return failure("unsupported_currency", "SplitNow works in EUR only");
        }
        if (amount < MIN || amount > MAX) {
            return failure("amount_out_of_range", "SplitNow finances 50.00 to 1500.00 EUR");
        }
        return null;
    }

    private static ResponseEntity<Failure> failure(String type, String message) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new Failure(Map.of("type", type, "message", message)));
    }

    private static ResponseEntity<Failure> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Failure(Map.of("type", "not_found", "message", "No such checkout")));
    }
}
