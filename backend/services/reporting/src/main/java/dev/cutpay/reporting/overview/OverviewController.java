package dev.cutpay.reporting.overview;

import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OverviewController {

    private final OverviewService overview;

    OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/v1/reporting/overview")
    OverviewService.Overview get(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "30") int days,
                                 @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        if (days < 1 || days > 365) {
            throw ApiException.invalidRequest("invalid_days", "days must be between 1 and 365", "days");
        }
        return overview.overview(jwt.getClaimAsString("mid"), RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode), days);
    }
}
