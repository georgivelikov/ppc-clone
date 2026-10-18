package dev.cutpay.bnplsim;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The demo controls: switch a provider off, slow it down, make it hang, or start over. */
@RestController
class AdminController {

    record SettingsRequest(Boolean outage, Long latencyMs, Double timeoutRate, Long decisionDelayMs) {
    }

    private final Providers providers;
    private final List<Resettable> stores;

    AdminController(Providers providers, List<Resettable> stores) {
        this.providers = providers;
        this.stores = stores;
    }

    @GetMapping("/admin/providers")
    Map<String, Providers.Settings> list() {
        return providers.all();
    }

    @PutMapping("/admin/providers/{code}")
    Providers.Settings update(@PathVariable String code, @RequestBody SettingsRequest request) {
        return providers.update(code, request.outage(), request.latencyMs(), request.timeoutRate(), request.decisionDelayMs());
    }

    /** Forgets every checkout, application and loan, and puts every provider back to how it started. */
    @PostMapping("/admin/reset")
    Map<String, Providers.Settings> reset() {
        stores.forEach(Resettable::reset);
        providers.reset();
        return providers.all();
    }
}
