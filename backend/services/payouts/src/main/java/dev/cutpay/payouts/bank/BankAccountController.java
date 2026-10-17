package dev.cutpay.payouts.bank;

import dev.cutpay.payouts.iban.Iban;
import dev.cutpay.payouts.payout.Sql;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import dev.cutpay.money.SupportedCurrencies;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where payouts go. Several accounts can exist; the newest active one in the payout's currency receives
 * the money. The full IBAN never leaves this service: responses carry only the country and last four digits.
 */
@RestController
class BankAccountController {

    record CreateRequest(@NotBlank String iban, @NotBlank String holderName, String currency) {
    }

    record BankAccountView(String id, String object, String country, String ibanLast4, String holderName,
                           String currency, String status, boolean livemode, Instant created) {
    }

    record BankAccountList(String object, List<BankAccountView> data) {
    }

    record Deleted(String id, String object, boolean deleted) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    BankAccountController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/v1/bank_accounts")
    BankAccountList list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean livemode) {
        List<BankAccountView> accounts = jdbc.sql("""
                        SELECT id, iban, holder_name, currency, status, livemode, created_at
                          FROM bank_accounts
                         WHERE merchant_id = :merchant AND livemode = :livemode AND status = 'active'
                         ORDER BY id DESC
                        """)
                .param("merchant", merchant(jwt))
                .param("livemode", livemode)
                .query((rs, n) -> view(rs.getString("id"), rs.getString("iban"), rs.getString("holder_name"),
                        rs.getString("currency").trim(), rs.getString("status"), rs.getBoolean("livemode"),
                        Sql.instant(rs, "created_at")))
                .list();
        return new BankAccountList("list", accounts);
    }

    @PostMapping("/v1/bank_accounts")
    @ResponseStatus(HttpStatus.CREATED)
    BankAccountView create(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean livemode,
                           @Valid @RequestBody CreateRequest request) {
        String iban = Iban.normalise(request.iban()).orElseThrow(() -> ApiException.invalidRequest(
                "invalid_iban", "That IBAN is not valid. Check the number and try again.", "iban"));
        String currency = request.currency() == null || request.currency().isBlank() ? "EUR"
                : request.currency().toUpperCase(Locale.ROOT);
        if (!SupportedCurrencies.isSupported(currency)) {
            throw ApiException.invalidRequest("unsupported_currency", "Unsupported currency " + currency,
                    "currency");
        }
        String id = Ids.generate("ba");
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO bank_accounts (id, merchant_id, livemode, iban, holder_name, currency, status,
                                                   created_at)
                        VALUES (:id, :merchant, :livemode, :iban, :holder, :currency, 'active', :createdAt)
                        """)
                .param("id", id)
                .param("merchant", merchant(jwt))
                .param("livemode", livemode)
                .param("iban", iban)
                .param("holder", request.holderName().trim())
                .param("currency", currency)
                .param("createdAt", Sql.ts(now))
                .update();
        return view(id, iban, request.holderName().trim(), currency, "active", livemode, now);
    }

    /** Removing keeps the row: past payouts still point at it. */
    @DeleteMapping("/v1/bank_accounts/{id}")
    Deleted delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        int updated = jdbc.sql("""
                        UPDATE bank_accounts SET status = 'removed'
                         WHERE id = :id AND merchant_id = :merchant AND status = 'active'
                        """)
                .param("id", id)
                .param("merchant", merchant(jwt))
                .update();
        if (updated == 0) {
            throw ApiException.notFound("bank_account_not_found", "No such bank account");
        }
        return new Deleted(id, "bank_account", true);
    }

    private static BankAccountView view(String id, String iban, String holder, String currency, String status,
                                        boolean livemode, Instant created) {
        return new BankAccountView(id, "bank_account", iban.substring(0, 2), Iban.last4(iban), holder, currency,
                status, livemode, created);
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }
}
