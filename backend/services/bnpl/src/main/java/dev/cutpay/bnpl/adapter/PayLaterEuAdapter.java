package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Applicant;
import dev.cutpay.bnpl.model.ApplicationRequest;
import dev.cutpay.bnpl.model.ProviderResult;
import dev.cutpay.bnpl.model.Money;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.QuoteRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * PayLaterEU has one RPC endpoint that always answers 200 and says in {@code ok} and {@code code} whether it worked, with
 * amounts as JSON numbers in euros. The whole price is paid after 30 days.
 */
@Component
class PayLaterEuAdapter implements BnplProviderAdapter {

    private static final ProviderInfo INFO = ProviderCatalog.find(ProviderCatalog.PAYLATEREU).orElseThrow();

    private final RestClient http;

    PayLaterEuAdapter(BnplProperties properties, RestClient.Builder builder) {
        this.http = ProviderHttp.client(builder, properties.providers().paylatereuUrl(), properties.providerTimeout());
    }

    @Override
    public ProviderInfo info() {
        return INFO;
    }

    @Override
    public List<String> requiredFields() {
        return List.of(Applicant.EMAIL);
    }

    @Override
    public List<Offer> quote(QuoteRequest request) {
        JsonNode body = ProviderHttp.call(INFO.code(), () -> http.post().uri("")
                .body(Map.of("action", "quote", "amount", Money.toDecimalNumber(request.amount()), "currency", request.currency()))
                .retrieve().body(JsonNode.class));
        if (body == null || !body.has("ok")) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "no ok in the answer");
        }
        if (!body.get("ok").asBoolean()) {
            throw new ProviderException(INFO.code(), Kind.REFUSED, "refused the quote: " + body.path("code").asString());
        }
        JsonNode data = body.get("data");
        int days = data.get("payInDays").asInt();
        return List.of(new Offer(INFO.code(), INFO.code() + "_" + days, 1, List.of(request.amount()), days, 0,
                request.amount() + Money.toMinor(data.get("customerCost").asString()),
                Money.toMinor(data.get("merchantFee").asString()), request.currency()));
    }

    @Override
    public ProviderResult createApplication(ApplicationRequest request) {
        JsonNode body = rpc(Map.of("action", "authorise", "amount", Money.toDecimalNumber(request.amount()),
                "currency", request.currency(), "customerEmail", request.applicant().email(), "reference", request.reference()));
        if (!body.get("ok").asBoolean()) {
            if ("DECLINED".equals(body.path("code").asString())) {
                return ProviderResult.declined(body.path("data").path("authId").asString(null), "declined_by_provider");
            }
            throw new ProviderException(INFO.code(), Kind.REFUSED, "refused the application: " + body.path("code").asString());
        }
        return approvedOrClosed(body.get("data"));
    }

    @Override
    public ProviderResult status(String providerRef) {
        JsonNode body = rpc(Map.of("action", "status", "authId", providerRef));
        if (!body.get("ok").asBoolean()) {
            throw new ProviderException(INFO.code(), Kind.REFUSED, "does not know the authorisation: " + body.path("code").asString());
        }
        return approvedOrClosed(body.get("data"));
    }

    @Override
    public void cancel(String providerRef) {
        JsonNode body = rpc(Map.of("action", "cancel", "authId", providerRef));
        if (!body.get("ok").asBoolean()) {
            throw new ProviderException(INFO.code(), Kind.REFUSED, "could not cancel: " + body.path("code").asString());
        }
    }

    @Override
    public void refund(String providerRef, long amount) {
        JsonNode body = rpc(Map.of("action", "refund", "authId", providerRef, "amount", Money.toDecimalNumber(amount)));
        if (!body.get("ok").asBoolean()) {
            throw new ProviderException(INFO.code(), Kind.REFUSED, "could not refund: " + body.path("code").asString());
        }
    }

    private JsonNode rpc(Map<String, Object> call) {
        JsonNode body = ProviderHttp.call(INFO.code(), () -> http.post().uri("").body(call).retrieve().body(JsonNode.class));
        if (body == null || !body.has("ok")) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "no ok in the answer");
        }
        return body;
    }

    private static ProviderResult approvedOrClosed(JsonNode data) {
        String id = data.get("authId").asString();
        return switch (data.get("state").asString()) {
            case "AUTHORISED" -> ProviderResult.approved(id);
            case "DECLINED" -> ProviderResult.declined(id, "declined_by_provider");
            case "CANCELED" -> ProviderResult.canceled(id);
            default -> throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "unknown state " + data.get("state").asString());
        };
    }
}
