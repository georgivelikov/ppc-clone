package dev.cutpay.identity.connected;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.identity.auth.AuthService;
import dev.cutpay.identity.domain.Merchant;
import dev.cutpay.identity.domain.Repositories.MerchantRepository;
import dev.cutpay.money.Iban;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connected accounts: the sub-merchants of a platform. Creating one announces it, so the ledger can attribute
 * the money sent to it and payouts can open a bank account for it from the IBAN.
 */
@RestController
class ConnectedAccountController {

    private static final Set<String> CAN_WRITE = Set.of("owner", "admin", "developer", "api");

    record CreateRequest(@NotBlank @Size(max = 160) String name, @Size(max = 254) String email, @NotBlank String iban) {
    }

    record UpdateRequest(@Size(max = 160) String name, @Size(max = 254) String email, String iban) {
    }

    record AccountView(String id, String object, boolean livemode, String name, String email, String country,
                       String currency, String ibanLast4, String status, Instant createdAt) {
        static AccountView of(ConnectedAccount a) {
            return new AccountView(a.getId(), "connected_account", a.isLivemode(), a.getName(), a.getEmail(),
                    a.getCountry(), a.getCurrency(), Iban.last4(a.getIban()), a.getStatus(), a.getCreatedAt());
        }
    }

    record AccountList(String object, List<AccountView> data, boolean hasMore) {
    }

    /** What payments needs before it sends money to an account. */
    record InternalView(String id, String status, String currency, boolean livemode) {
    }

    private final ConnectedAccountRepository accounts;
    private final MerchantRepository merchants;
    private final OutboxWriter outbox;

    ConnectedAccountController(ConnectedAccountRepository accounts, MerchantRepository merchants, OutboxWriter outbox) {
        this.accounts = accounts;
        this.merchants = merchants;
        this.outbox = outbox;
    }

    @PostMapping("/v1/connected_accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AccountView create(@AuthenticationPrincipal Jwt jwt,
                       @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                       @Valid @RequestBody CreateRequest request) {
        requireWriter(jwt);
        String iban = Iban.normalise(request.iban()).orElseThrow(() -> ApiException.invalidRequest("invalid_iban",
                "That IBAN is not valid. Check the number and try again.", "iban"));
        Merchant platform = merchants.findById(platformId(jwt))
                .orElseThrow(() -> ApiException.notFound("merchant_not_found", "Merchant not found"));
        var account = new ConnectedAccount(Ids.generate("acct"), platform.getId(),
                RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode), request.name().trim(),
                blankToNull(request.email()), iban, iban.substring(0, 2), platform.getDefaultCurrency(), Instant.now());
        accounts.save(account);
        announce("ConnectedAccountCreated", account);
        return AccountView.of(account);
    }

    @GetMapping("/v1/connected_accounts")
    @Transactional(readOnly = true)
    AccountList list(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                     @RequestParam(name = "starting_after", required = false) String startingAfter,
                     @RequestParam(defaultValue = "20") int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        var found = accounts.page(platformId(jwt), RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode),
                startingAfter == null || startingAfter.isBlank() ? null : startingAfter, PageRequest.of(0, size + 1));
        return new AccountList("list", found.stream().limit(size).map(AccountView::of).toList(), found.size() > size);
    }

    @GetMapping("/v1/connected_accounts/{id}")
    @Transactional(readOnly = true)
    AccountView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return AccountView.of(owned(jwt, id));
    }

    @PatchMapping("/v1/connected_accounts/{id}")
    @Transactional
    AccountView update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody UpdateRequest request) {
        requireWriter(jwt);
        ConnectedAccount account = owned(jwt, id);
        if (!account.isActive()) {
            throw ApiException.conflict("account_disabled", "This account is disabled", null);
        }
        String iban = null;
        if (request.iban() != null && !request.iban().isBlank()) {
            iban = Iban.normalise(request.iban()).orElseThrow(() -> ApiException.invalidRequest("invalid_iban",
                    "That IBAN is not valid. Check the number and try again.", "iban"));
            if (!iban.startsWith(account.getCountry())) {
                throw ApiException.invalidRequest("iban_country_changed", "The IBAN has to stay in the same country", "iban");
            }
        }
        account.update(blankToNull(request.name()), blankToNull(request.email()), iban, Instant.now());
        announce("ConnectedAccountUpdated", account);
        return AccountView.of(account);
    }

    /** Stops sending money to the account. What it already earned is still paid out. */
    @PostMapping("/v1/connected_accounts/{id}/disable")
    @Transactional
    AccountView disable(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        requireWriter(jwt);
        ConnectedAccount account = owned(jwt, id);
        if (account.isActive()) {
            account.disable(Instant.now());
            announce("ConnectedAccountDisabled", account);
        }
        return AccountView.of(account);
    }

    /** Service-to-service. The gateway does not route {@code /internal/**}. */
    @GetMapping("/internal/platforms/{platformId}/connected_accounts/{id}")
    @Transactional(readOnly = true)
    InternalView internal(@PathVariable String platformId, @PathVariable String id) {
        ConnectedAccount account = accounts.findById(id).filter(a -> a.getPlatformMerchantId().equals(platformId))
                .orElseThrow(() -> ApiException.notFound("connected_account_not_found", "No such connected account"));
        return new InternalView(account.getId(), account.getStatus(), account.getCurrency(), account.isLivemode());
    }

    private ConnectedAccount owned(Jwt jwt, String id) {
        return accounts.findById(id).filter(a -> a.getPlatformMerchantId().equals(platformId(jwt)))
                .orElseThrow(() -> ApiException.notFound("connected_account_not_found", "No such connected account"));
    }

    private void announce(String eventType, ConnectedAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account_id", account.getId());
        payload.put("platform_merchant_id", account.getPlatformMerchantId());
        payload.put("name", account.getName());
        payload.put("iban", account.getIban());
        payload.put("country", account.getCountry());
        payload.put("currency", account.getCurrency());
        outbox.write(AuthService.MERCHANT_TOPIC, eventType, account.getId(), account.getPlatformMerchantId(),
                account.isLivemode(), payload);
    }

    private static void requireWriter(Jwt jwt) {
        if (!CAN_WRITE.contains(jwt.getClaimAsString("role"))) {
            throw ApiException.forbidden("insufficient_role", "Your role cannot change connected accounts");
        }
    }

    private static String platformId(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
