package dev.cutpay.acquirer;

import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * A pretend BLIK network and bank app. The customer types a six digit code from their banking app; the bank then
 * shows a request to confirm in the app. Confirming is the customer's job, so the answer arrives later, by a call
 * back to payments, instead of in the reply.
 *
 * <ul>
 *   <li>{@code 777123}: a request waits in the bank app. It is approved by itself after a few seconds unless someone
 *       answers it first on the bank app page.</li>
 *   <li>{@code 000000}: the bank declines at once.</li>
 *   <li>any other six digits: not a valid code.</li>
 * </ul>
 */
@RestController
class BlikController {

    private static final Logger log = LoggerFactory.getLogger(BlikController.class);

    static final String APPROVED_CODE = "777123";
    static final String DECLINED_CODE = "000000";

    record ChargeRequest(@NotBlank String code, @Min(1) long amount, @NotBlank String currency, String reference) {
    }

    record ChargeResponse(String status, String acquirerRef, String declineCode, String message) {
    }

    record BankRequest(String id, long amount, String currency, String reference, Instant createdAt) {
    }

    private final Map<String, BankRequest> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "blik-auto-approve");
        thread.setDaemon(true);
        return thread;
    });
    private final AcquirerProperties properties;
    private final AcquirerController acquirer;
    private final RestClient payments;

    BlikController(AcquirerProperties properties, AcquirerController acquirer, RestClient.Builder builder,
                   @Value("${cutpay.payments-url}") String paymentsUrl) {
        this.properties = properties;
        this.acquirer = acquirer;
        this.payments = builder.baseUrl(paymentsUrl).build();
    }

    @PostMapping("/blik/charge")
    ChargeResponse charge(@Valid @RequestBody ChargeRequest request) {
        if (!"PLN".equals(request.currency())) {
            return new ChargeResponse("declined", null, "currency_not_supported", "BLIK only works in PLN.");
        }
        if (!request.code().matches("[0-9]{6}")) {
            return new ChargeResponse("invalid_code", null, "invalid_blik_code", "A BLIK code has six digits.");
        }
        if (DECLINED_CODE.equals(request.code())) {
            return new ChargeResponse("declined", null, "blik_declined", "Your bank declined the BLIK payment.");
        }
        if (!APPROVED_CODE.equals(request.code())) {
            return new ChargeResponse("invalid_code", null, "invalid_blik_code",
                    "That BLIK code is not valid or has expired.");
        }
        String id = "blik_" + UUID.randomUUID().toString().replace("-", "");
        pending.put(id, new BankRequest(id, request.amount(), request.currency(), request.reference(), Instant.now()));
        timer.schedule(() -> resolve(id, true), properties.blikDelay().toMillis(), TimeUnit.MILLISECONDS);
        return new ChargeResponse("requires_action", id, null, "Confirm the payment in your banking app");
    }

    /** The pretend bank app, a page for a person to answer a request on. */
    @GetMapping(value = "/sim/blik-app", produces = "text/html;charset=UTF-8")
    org.springframework.core.io.Resource bankApp() {
        return new org.springframework.core.io.ClassPathResource("pages/blik-app.html");
    }

    /** What the pretend bank app shows: the requests waiting for the customer. */
    @GetMapping("/blik/requests")
    List<BankRequest> requests() {
        return pending.values().stream().sorted(Comparator.comparing(BankRequest::createdAt)).toList();
    }

    @PostMapping("/blik/requests/{id}/approve")
    Map<String, String> approve(@PathVariable String id) {
        return answer(id, true);
    }

    @PostMapping("/blik/requests/{id}/reject")
    Map<String, String> reject(@PathVariable String id) {
        return answer(id, false);
    }

    private Map<String, String> answer(String id, boolean approved) {
        if (!resolve(id, approved)) {
            throw ApiException.notFound("blik_request_not_found", "Unknown or already answered request");
        }
        return Map.of("status", approved ? "approved" : "declined");
    }

    /** Takes the request off the list exactly once; whoever gets there first (the customer or the timer) decides. */
    private boolean resolve(String id, boolean approved) {
        BankRequest request = pending.remove(id);
        if (request == null) {
            return false;
        }
        if (approved) {
            acquirer.recordAuthorization(id, request.reference(), request.amount(), request.currency());
        }
        try {
            payments.post().uri("/internal/blik/callback")
                    .body(Map.of("acquirer_ref", id, "status", approved ? "approved" : "declined",
                            "decline_code", approved ? "" : "blik_rejected",
                            "message", approved ? "" : "You rejected the payment in your banking app."))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Could not deliver the BLIK answer for {} to payments", id, e);
            if (Thread.currentThread().getName().equals("blik-auto-approve")) {
                return true;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach payments");
        }
        return true;
    }
}
