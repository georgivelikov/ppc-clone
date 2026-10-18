package dev.cutpay.bnplsim;

import static dev.cutpay.bnplsim.Providers.KREDITKO;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * KreditKo: loans of 3 to 36 months at 12.9% a year for the customer and 4.5% for the merchant, EUR 200 to 15,000.
 * Camel-case JSON in cents, with the applicant's national ID and monthly income in the request. It never calls back:
 * a loan is polled. A loan above EUR 5,000 goes to manual review, which a person resolves (in the simulator, through
 * the admin API) or which resolves by itself after a delay.
 */
@RestController
class KreditKoController implements Resettable {

    static final long MIN = 20_000;
    static final long MAX = 1_500_000;
    static final long REVIEW_ABOVE = 500_000;
    static final int FEE_BPS = 450;
    static final double APR = 12.9;
    static final int MIN_MONTHS = 3;
    static final int MAX_MONTHS = 36;

    record Applicant(@JsonProperty("nationalId") String nationalId, @JsonProperty("monthlyIncomeMinor") Long monthlyIncomeMinor,
                     @JsonProperty("email") String email) {
    }

    record SimulationRequest(@JsonProperty("amountMinor") Long amountMinor, @JsonProperty("termMonths") Integer termMonths) {
    }

    record LoanRequest(@JsonProperty("amountMinor") Long amountMinor, @JsonProperty("currency") String currency,
                       @JsonProperty("termMonths") Integer termMonths, @JsonProperty("reference") String reference,
                       @JsonProperty("applicant") Applicant applicant) {
    }

    record RefundRequest(@JsonProperty("amountMinor") Long amountMinor) {
    }

    record DecisionRequest(@JsonProperty("approve") boolean approve) {
    }

    private static final class Loan {
        final String id = "KK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String status;
        String reason;
        long amount;
        int term;
        String reference;
        boolean incomeOk;
        Instant reviewDue;
        long refunded;
    }

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();
    private final Providers providers;

    KreditKoController(Providers providers) {
        this.providers = providers;
    }

    @Override
    public void reset() {
        loans.clear();
    }

    @PostMapping("/kreditko/simulations")
    ResponseEntity<?> simulate(@RequestBody SimulationRequest request) {
        providers.enter(KREDITKO);
        ResponseEntity<?> refused = refuse(request.amountMinor(), request.termMonths(), "EUR");
        if (refused != null) {
            return refused;
        }
        List<Long> payments = Instalments.annuity(request.amountMinor(), request.termMonths(), APR);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("termMonths", request.termMonths());
        body.put("monthlyMinor", payments.get(0));
        body.put("aprPercent", APR);
        body.put("totalPayableMinor", Instalments.sum(payments));
        body.put("feeMinor", Instalments.fee(request.amountMinor(), FEE_BPS));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/kreditko/loans")
    ResponseEntity<?> apply(@RequestBody LoanRequest request) {
        providers.enter(KREDITKO);
        ResponseEntity<?> refused = refuse(request.amountMinor(), request.termMonths(),
                request.currency() == null ? "EUR" : request.currency());
        if (refused != null) {
            return refused;
        }
        List<Map<String, String>> problems = new ArrayList<>();
        Applicant applicant = request.applicant();
        if (applicant == null || applicant.nationalId() == null || !applicant.nationalId().matches("[0-9A-Za-z]{6,16}")) {
            problems.add(Map.of("field", "applicant.nationalId", "message", "A national ID of 6 to 16 letters or digits is required"));
        }
        if (applicant == null || applicant.monthlyIncomeMinor() == null || applicant.monthlyIncomeMinor() <= 0) {
            problems.add(Map.of("field", "applicant.monthlyIncomeMinor", "message", "Monthly income is required"));
        }
        if (!problems.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "VALIDATION", "problems", problems));
        }

        Loan loan = new Loan();
        loan.amount = request.amountMinor();
        loan.term = request.termMonths();
        loan.reference = request.reference();
        long monthly = Instalments.annuity(loan.amount, loan.term, APR).get(0);
        loan.incomeOk = applicant.monthlyIncomeMinor() >= monthly * 3;
        if (loan.amount > REVIEW_ABOVE) {
            loan.status = "UNDER_REVIEW";
            loan.reviewDue = Instant.now().plus(Duration.ofMillis(providers.get(KREDITKO).decisionDelayMs()));
        } else {
            decide(loan, loan.incomeOk);
        }
        loans.put(loan.id, loan);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(loan));
    }

    @GetMapping("/kreditko/loans/{id}")
    ResponseEntity<?> get(@PathVariable String id) {
        providers.enter(KREDITKO);
        Loan loan = loans.get(id);
        if (loan == null) {
            return notFound();
        }
        synchronized (loan) {
            if ("UNDER_REVIEW".equals(loan.status) && !Instant.now().isBefore(loan.reviewDue)) {
                decide(loan, loan.incomeOk);
            }
            return ResponseEntity.ok(view(loan));
        }
    }

    @PostMapping("/kreditko/loans/{id}/cancel")
    ResponseEntity<?> cancel(@PathVariable String id) {
        providers.enter(KREDITKO);
        Loan loan = loans.get(id);
        if (loan == null) {
            return notFound();
        }
        synchronized (loan) {
            if (!"APPROVED".equals(loan.status) && !"UNDER_REVIEW".equals(loan.status)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "NOT_CANCELABLE"));
            }
            loan.status = "CANCELED";
            return ResponseEntity.ok(view(loan));
        }
    }

    @PostMapping("/kreditko/loans/{id}/refunds")
    ResponseEntity<?> refund(@PathVariable String id, @RequestBody RefundRequest request) {
        providers.enter(KREDITKO);
        Loan loan = loans.get(id);
        if (loan == null) {
            return notFound();
        }
        synchronized (loan) {
            if (!"APPROVED".equals(loan.status)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "NOT_REFUNDABLE"));
            }
            if (request.amountMinor() == null || request.amountMinor() < 1 || loan.refunded + request.amountMinor() > loan.amount) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("code", "REFUND_TOO_LARGE"));
            }
            loan.refunded += request.amountMinor();
            return ResponseEntity.status(HttpStatus.CREATED).body(view(loan));
        }
    }

    /** What a human reviewer does: approve or reject a loan that is waiting. Not part of the provider's public API. */
    @PostMapping("/admin/kreditko/loans/{id}/decision")
    ResponseEntity<?> decision(@PathVariable String id, @RequestBody DecisionRequest request) {
        Loan loan = loans.get(id);
        if (loan == null) {
            return notFound();
        }
        synchronized (loan) {
            if (!"UNDER_REVIEW".equals(loan.status)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "NOT_IN_REVIEW"));
            }
            decide(loan, request.approve());
            return ResponseEntity.ok(view(loan));
        }
    }

    private static void decide(Loan loan, boolean approve) {
        loan.status = approve ? "APPROVED" : "REJECTED";
        loan.reason = approve ? null : (loan.incomeOk ? "declined_by_reviewer" : "insufficient_income");
    }

    private static Map<String, Object> view(Loan loan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loanId", loan.id);
        body.put("status", loan.status);
        body.put("reason", loan.reason);
        body.put("amountMinor", loan.amount);
        body.put("termMonths", loan.term);
        body.put("feeMinor", Instalments.fee(loan.amount, FEE_BPS));
        body.put("refundedMinor", loan.refunded);
        body.put("reference", loan.reference);
        return body;
    }

    private static ResponseEntity<?> refuse(Long amount, Integer term, String currency) {
        if (!"EUR".equalsIgnoreCase(currency)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("code", "UNSUPPORTED_CURRENCY"));
        }
        if (amount == null || amount < MIN || amount > MAX) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("code", "AMOUNT_OUT_OF_RANGE",
                    "message", "KreditKo finances 200.00 to 15000.00 EUR"));
        }
        if (term == null || term < MIN_MONTHS || term > MAX_MONTHS) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("code", "TERM_OUT_OF_RANGE",
                    "message", "KreditKo offers 3 to 36 months"));
        }
        return null;
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "UNKNOWN_LOAN"));
    }
}
