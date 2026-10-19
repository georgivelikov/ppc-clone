package dev.cutpay.payments.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The ways of paying a merchant offers. */
@RestController
class PaymentMethodSettingsController {

    record MethodView(String method, boolean enabled, boolean locked) {
    }

    record SettingsView(String object, List<MethodView> data) {
    }

    record Change(@NotNull String method, @NotNull Boolean enabled) {
    }

    record UpdateRequest(@NotEmpty List<@Valid Change> methods) {
    }

    private final PaymentMethodSettings settings;

    PaymentMethodSettingsController(PaymentMethodSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/v1/payment_method_settings")
    SettingsView get(@AuthenticationPrincipal Jwt jwt) {
        return view(settings.list(jwt.getClaimAsString("mid")));
    }

    @PutMapping("/v1/payment_method_settings")
    SettingsView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateRequest request) {
        Map<String, Boolean> changes = new LinkedHashMap<>();
        request.methods().forEach(change -> changes.put(change.method(), change.enabled()));
        return view(settings.update(jwt.getClaimAsString("mid"), changes));
    }

    private static SettingsView view(List<PaymentMethodSettings.Setting> list) {
        return new SettingsView("payment_method_settings",
                list.stream().map(s -> new MethodView(s.method(), s.enabled(), s.locked())).toList());
    }
}
