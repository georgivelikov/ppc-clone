package dev.cutpay.billing.credit;

import dev.cutpay.billing.credit.CreditRepository.Balance;
import dev.cutpay.billing.credit.CreditRepository.Entry;
import dev.cutpay.web.RequestMode;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What credit a customer holds and how it got there. */
@RestController
class CreditController {

    record CreditView(String object, String customer, List<Balance> balances, List<Entry> history) {
    }

    private final CreditService service;

    CreditController(CreditService service) {
        this.service = service;
    }

    @GetMapping("/v1/credit_balances")
    CreditView get(@AuthenticationPrincipal Jwt jwt,
                   @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                   @RequestParam String customer) {
        String merchant = jwt.getClaimAsString("mid");
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        return new CreditView("credit_balance", customer, service.balances(merchant, livemode, customer),
                service.history(merchant, livemode, customer));
    }
}
