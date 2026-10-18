package dev.cutpay.bnpl.offers;

import dev.cutpay.bnpl.offers.DecisionRepository.Stored;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The merchant asking why a provider was chosen for a payment. */
@RestController
class DecisionController {

    record DecisionView(String object, String id, String payment, String strategy, long amount, String currency,
                        String country, String chosenProvider, String reason, JsonNode ranked, JsonNode excluded,
                        Instant createdAt) {
    }

    private final DecisionRepository decisions;
    private final JsonMapper json;

    DecisionController(DecisionRepository decisions, JsonMapper json) {
        this.decisions = decisions;
        this.json = json;
    }

    @GetMapping("/v1/bnpl/routing_decisions/{paymentIntent}")
    DecisionView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String paymentIntent,
                     @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        Stored d = decisions.latest(jwt.getClaimAsString("mid"), livemode, paymentIntent)
                .orElseThrow(() -> ApiException.notFound("routing_decision_not_found", "No routing decision for this payment"));
        JsonNode detail = json.readTree(d.detailJson());
        return new DecisionView("routing_decision", d.id(), d.paymentId(), d.strategy(), d.amount(), d.currency(), d.country(),
                d.chosenProvider(), d.reason(), detail.get("ranked"), detail.get("excluded"), d.createdAt());
    }
}
