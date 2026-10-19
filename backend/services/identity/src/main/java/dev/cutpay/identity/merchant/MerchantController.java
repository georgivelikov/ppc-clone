package dev.cutpay.identity.merchant;

import dev.cutpay.identity.audit.AuditLog;
import dev.cutpay.identity.auth.AuthDtos.MerchantView;
import dev.cutpay.identity.domain.Merchant;
import dev.cutpay.identity.domain.Repositories.MerchantRepository;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MerchantController {

    private static final Set<String> CAN_EDIT = Set.of("owner", "admin");

    private final MerchantRepository merchants;
    private final AuditLog audit;

    public MerchantController(MerchantRepository merchants, AuditLog audit) {
        this.merchants = merchants;
        this.audit = audit;
    }

    public record UpdateMerchantRequest(@Size(min = 1, max = 160) String displayName, String defaultCurrency) {
    }

    @GetMapping("/v1/merchant")
    @Transactional(readOnly = true)
    MerchantView get(@AuthenticationPrincipal Jwt jwt) {
        return MerchantView.of(current(jwt));
    }

    @PatchMapping("/v1/merchant")
    @Transactional
    MerchantView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateMerchantRequest request) {
        if (!CAN_EDIT.contains(jwt.getClaimAsString("role"))) {
            throw ApiException.forbidden("insufficient_role", "Only owners and admins can edit the business profile");
        }
        Merchant merchant = current(jwt);
        if (request.displayName() != null && !request.displayName().isBlank()) {
            merchant.setDisplayName(request.displayName().trim());
        }
        if (request.defaultCurrency() != null) {
            if (!SupportedCurrencies.isSupported(request.defaultCurrency())) {
                throw ApiException.invalidRequest("unsupported_currency", "Currency is not supported",
                        "default_currency");
            }
            merchant.setDefaultCurrency(request.defaultCurrency().toUpperCase(Locale.ROOT));
        }
        audit.record(merchant.getId(), jwt, "merchant.updated", "merchant", merchant.getId(),
                request.displayName() != null ? "name" : "default currency");
        return MerchantView.of(merchant);
    }

    private Merchant current(Jwt jwt) {
        return merchants.findById(jwt.getClaimAsString("mid"))
                .orElseThrow(() -> ApiException.notFound("merchant_not_found", "Merchant not found"));
    }
}
