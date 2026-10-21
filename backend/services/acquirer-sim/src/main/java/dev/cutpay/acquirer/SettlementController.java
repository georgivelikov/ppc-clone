package dev.cutpay.acquirer;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The acquirer's settlement file. Not exposed by the gateway; the reconciliation job fetches it directly. */
@RestController
class SettlementController {

    private final SettlementBook book;

    SettlementController(SettlementBook book) {
        this.book = book;
    }

    record Authorization(String status, String acquirerRef, long amount, String currency) {
    }

    /**
     * What the acquirer did with a payment, asked by the reference the PSP sent. This is how the PSP finds out what happened
     * to a request whose answer it never saw.
     */
    @GetMapping("/authorizations/by-reference/{reference}")
    Authorization byReference(@org.springframework.web.bind.annotation.PathVariable String reference) {
        return book.findByReference(reference)
                .map(c -> new Authorization("approved", c.ref, c.settled(), c.currency))
                .orElseThrow(() -> dev.cutpay.web.ApiException.notFound("authorization_not_found", "No charge for that reference"));
    }

    /** {@code date} is a UTC day (2026-10-21); without it the file covers everything the simulator remembers. */
    @GetMapping(value = "/settlement", produces = "text/csv;charset=UTF-8")
    String settlement(@RequestParam(required = false) LocalDate date) {
        return book.csv(date);
    }
}
