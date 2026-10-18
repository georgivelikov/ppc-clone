package dev.cutpay.payments.customer;

import dev.cutpay.payments.customer.CustomerDtos.ConfirmSetupRequest;
import dev.cutpay.payments.customer.CustomerDtos.CreateSetupIntentRequest;
import dev.cutpay.payments.customer.CustomerDtos.SetupIntentView;
import dev.cutpay.payments.customer.CustomerDtos.SetupSessionView;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SetupIntentController {

    private final SetupIntentService setups;

    SetupIntentController(SetupIntentService setups) {
        this.setups = setups;
    }

    @PostMapping("/v1/setup_intents")
    @ResponseStatus(HttpStatus.CREATED)
    SetupIntentView create(@AuthenticationPrincipal Jwt jwt,
                           @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                           @Valid @RequestBody CreateSetupIntentRequest request) {
        return setups.create(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), request);
    }

    @GetMapping("/v1/setup_intents/{id}")
    SetupIntentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return setups.get(merchant(jwt), claim(jwt), id);
    }

    @PostMapping("/v1/setup_intents/{id}/cancel")
    SetupIntentView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return setups.cancel(merchant(jwt), claim(jwt), id);
    }

    // --- public: the hosted card page, authorised by knowing the client secret ----------------------

    @GetMapping("/checkout/setup_intents/{clientSecret}")
    SetupSessionView session(@PathVariable String clientSecret) {
        return setups.session(clientSecret);
    }

    @PostMapping("/checkout/setup_intents/{clientSecret}/confirm")
    SetupSessionView confirm(@PathVariable String clientSecret, @Valid @RequestBody ConfirmSetupRequest request) {
        return setups.confirm(clientSecret, request.token());
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    private static Boolean claim(Jwt jwt) {
        return jwt.getClaimAsBoolean("livemode");
    }
}
