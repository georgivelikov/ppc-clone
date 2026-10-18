package dev.cutpay.bnpl.settings;

import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.routing.MerchantRouting;
import dev.cutpay.bnpl.routing.MerchantRouting.Weights;
import dev.cutpay.bnpl.settings.SettingsService.Changes;
import dev.cutpay.bnpl.settings.SettingsService.ProviderChange;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Which providers a merchant offers, in which order, and how the best one is picked. */
@RestController
class SettingsController {

    record ProviderView(String code, String name, boolean enabled, int priority, long minAmount, long maxAmount,
                        Set<String> currencies, Set<String> countries) {
    }

    record SettingsView(String object, String routingMode, Weights weights, List<ProviderView> providers) {
    }

    record ProviderInput(String code, Boolean enabled, Integer priority) {
    }

    record UpdateSettings(String routingMode, Weights weights, @Valid List<ProviderInput> providers) {
    }

    private final SettingsService service;

    SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/v1/bnpl/settings")
    SettingsView get(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        return view(service.get(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode)));
    }

    @PutMapping("/v1/bnpl/settings")
    SettingsView update(@AuthenticationPrincipal Jwt jwt,
                        @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                        @Valid @RequestBody UpdateSettings request) {
        List<ProviderChange> providers = request.providers() == null ? null
                : request.providers().stream().map(p -> new ProviderChange(p.code(), p.enabled(), p.priority())).toList();
        return view(service.update(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode),
                new Changes(request.routingMode(), request.weights(), providers)));
    }

    static SettingsView view(MerchantRouting routing) {
        List<ProviderView> providers = routing.providers().stream().map(setting -> {
            var info = ProviderCatalog.find(setting.code()).orElseThrow();
            return new ProviderView(info.code(), info.name(), setting.enabled(), setting.priority(), info.minAmount(),
                    info.maxAmount(), new TreeSet<>(info.currencies()), new TreeSet<>(info.countries()));
        }).toList();
        return new SettingsView("bnpl_settings", routing.mode().wire(), routing.weights(), providers);
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    private static Boolean claim(Jwt jwt) {
        return jwt.getClaimAsBoolean("livemode");
    }
}
