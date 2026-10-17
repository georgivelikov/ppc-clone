package dev.cutpay.identity.apikey;

import dev.cutpay.identity.domain.ApiKey;
import dev.cutpay.identity.security.JwtService;
import dev.cutpay.identity.security.JwtService.IssuedToken;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Locale;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called only by the gateway. The gateway does not route {@code /internal/**}, so the internet cannot reach it.
 */
@RestController
public class InternalApiKeyController {

    private final ApiKeyService service;
    private final JwtService jwt;

    public InternalApiKeyController(ApiKeyService service, JwtService jwt) {
        this.service = service;
        this.jwt = jwt;
    }

    public record AuthenticateRequest(@NotBlank String key) {
    }

    public record AuthenticatedKey(String keyId, String merchantId, boolean livemode, String type,
                                   List<String> scopes, String token, long expiresIn) {
    }

    @PostMapping("/internal/api_keys/authenticate")
    @Transactional
    AuthenticatedKey authenticate(@Valid @RequestBody AuthenticateRequest request) {
        ApiKey key = service.authenticate(request.key())
                .orElseThrow(() -> ApiException.unauthorized("invalid_api_key", "Invalid API key"));
        String type = key.getType().name().toLowerCase(Locale.ROOT);
        IssuedToken token = jwt.issueForApiKey(key.getId(), key.getMerchantId(), key.isLivemode(), type,
                key.getScopes());
        return new AuthenticatedKey(key.getId(), key.getMerchantId(), key.isLivemode(), type, key.getScopes(),
                token.value(), token.expiresInSeconds());
    }
}
