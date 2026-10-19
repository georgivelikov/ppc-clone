package dev.cutpay.acquirer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The card network's side of a chargeback: it reads the merchant's evidence and decides. A real network has people
 * (or rules) behind this and takes weeks; here the words decide, so a test can pick the outcome.
 *
 * <ul>
 *   <li>evidence that contains {@code winning_evidence}: the merchant wins and the money goes back;</li>
 *   <li>anything else, or no evidence: the cardholder wins.</li>
 * </ul>
 */
@RestController
class DisputeController {

    static final String WINNING_WORD = "winning_evidence";

    record EvaluateRequest(@NotBlank String disputeId, String evidence, Integer files) {
    }

    record EvaluateResponse(String outcome, String reason) {
    }

    @PostMapping("/disputes/evaluate")
    EvaluateResponse evaluate(@Valid @RequestBody EvaluateRequest request) {
        String evidence = request.evidence() == null ? "" : request.evidence().toLowerCase(Locale.ROOT);
        if (evidence.contains(WINNING_WORD)) {
            return new EvaluateResponse("won", "The evidence shows the customer received what they paid for.");
        }
        return new EvaluateResponse("lost", evidence.isBlank() && (request.files() == null || request.files() == 0)
                ? "No evidence was sent." : "The evidence did not show that the customer received what they paid for.");
    }
}
