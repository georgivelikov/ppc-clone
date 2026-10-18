package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Applicant;
import dev.cutpay.bnpl.model.ApplicationRequest;
import dev.cutpay.bnpl.model.Money;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.ProviderResult;
import dev.cutpay.bnpl.model.QuoteRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Lendo writes amounts as decimal strings in euros, takes applications form-encoded, offers monthly loans (one monthly
 * figure stands for every month), decides later and reports by a signed, form-encoded callback.
 */
@Component
class LendoAdapter implements BnplProviderAdapter {

    private static final ProviderInfo INFO = ProviderCatalog.find(ProviderCatalog.LENDO).orElseThrow();

    private final RestClient http;
    private final String callbackUrl;
    private final String secret;

    LendoAdapter(BnplProperties properties, RestClient.Builder builder) {
        this.callbackUrl = properties.callbackBaseUrl() + "/bnpl/callback/lendo";
        this.secret = properties.providers().lendoSecret();
        this.http = ProviderHttp.client(builder, properties.providers().lendoUrl(), properties.providerTimeout());
    }

    @Override
    public ProviderInfo info() {
        return INFO;
    }

    @Override
    public List<String> requiredFields() {
        return List.of(Applicant.EMAIL, Applicant.BIRTHDATE);
    }

    @Override
    public List<Offer> quote(QuoteRequest request) {
        JsonNode body = ProviderHttp.call(INFO.code(), () -> http.get()
                .uri("/offers?amount={amount}&currency={currency}", Money.toDecimal(request.amount()), request.currency())
                .retrieve().body(JsonNode.class));
        JsonNode offers = body == null ? null : body.get("offers");
        if (offers == null || !offers.isArray()) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "no offers in the answer");
        }
        List<Offer> result = new ArrayList<>();
        for (JsonNode offer : offers) {
            int months = offer.get("months").asInt();
            long monthly = Money.toMinor(offer.get("monthly").asString());
            long total = Money.toMinor(offer.get("total_payable").asString());
            result.add(new Offer(INFO.code(), INFO.code() + "_" + months, months,
                    Collections.nCopies(months, monthly), 30, offer.get("apr").asDouble(), total,
                    Money.toMinor(offer.get("merchant_fee").asString()), request.currency()));
        }
        return result;
    }

    @Override
    public ProviderResult createApplication(ApplicationRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", Money.toDecimal(request.amount()));
        form.add("currency", request.currency());
        form.add("months", String.valueOf(request.instalmentCount()));
        form.add("birthdate", request.applicant().birthdate());
        form.add("email", request.applicant().email());
        form.add("reference", request.reference());
        form.add("callback_url", callbackUrl);
        JsonNode body = ProviderHttp.call(INFO.code(), () -> http.post().uri("/applications")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class));
        if (body == null || !body.has("application_id")) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "no application id in the answer");
        }
        return fromStatus(body.get("application_id").asString(), body.path("status").asString());
    }

    @Override
    public ProviderResult status(String providerRef) {
        JsonNode body = ProviderHttp.call(INFO.code(), () -> http.get().uri("/applications/{id}", providerRef)
                .retrieve().body(JsonNode.class));
        if (body == null || !body.has("status")) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "no status in the answer");
        }
        return fromStatus(providerRef, body.get("status").asString());
    }

    @Override
    public void cancel(String providerRef) {
        ProviderHttp.call(INFO.code(), () -> http.post().uri("/applications/{id}/cancel", providerRef).retrieve().toBodilessEntity());
    }

    @Override
    public void refund(String providerRef, long amount) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", Money.toDecimal(amount));
        ProviderHttp.call(INFO.code(), () -> http.post().uri("/applications/{id}/refund", providerRef)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity());
    }

    /** Lendo signs the exact body it sends with HMAC-SHA256 and a shared key; anything else is not from Lendo. */
    @Override
    public Optional<Callback> handleCallback(Map<String, String> headers, String body) {
        String signature = headers.get("x-lendo-signature");
        if (signature == null
                || !MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("The signature of the Lendo callback does not match");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                fields.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        String id = fields.get("application_id");
        String outcome = fields.get("outcome");
        String reference = fields.get("reference");
        if (id == null || outcome == null || reference == null) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "the callback is missing fields");
        }
        return Optional.of(new Callback(reference, fromStatus(id, outcome)));
    }

    private static ProviderResult fromStatus(String id, String status) {
        return switch (status) {
            case "processing" -> ProviderResult.pending(id);
            case "accepted" -> ProviderResult.approved(id);
            case "rejected" -> ProviderResult.declined(id, "credit_check_failed");
            case "canceled" -> ProviderResult.canceled(id);
            default -> throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "unknown status " + status);
        };
    }

    String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
