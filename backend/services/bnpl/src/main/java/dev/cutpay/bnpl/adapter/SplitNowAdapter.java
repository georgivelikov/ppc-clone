package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Applicant;
import dev.cutpay.bnpl.model.ApplicationRequest;
import dev.cutpay.bnpl.model.ProviderResult;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.QuoteRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** SplitNow speaks snake_case JSON in cents and answers at once. */
@Component
class SplitNowAdapter implements BnplProviderAdapter {

    private static final ProviderInfo INFO = ProviderCatalog.find(ProviderCatalog.SPLITNOW).orElseThrow();

    private final RestClient http;

    SplitNowAdapter(BnplProperties properties, RestClient.Builder builder) {
        this.http = ProviderHttp.client(builder, properties.providers().splitnowUrl(), properties.providerTimeout());
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
        JsonNode body = ProviderHttp.call(INFO.code(), () -> http.post().uri("/quotes")
                .body(Map.of("amount_cents", request.amount(), "currency", request.currency()))
                .retrieve().body(JsonNode.class));
        JsonNode plans = body == null ? null : body.get("plans");
        if (plans == null || !plans.isArray()) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "no plans in the quote");
        }
        List<Offer> offers = new ArrayList<>();
        for (JsonNode plan : plans) {
            List<Long> instalments = new ArrayList<>();
            plan.get("instalment_cents").forEach(cents -> instalments.add(cents.asLong()));
            int count = plan.get("instalments").asInt();
            offers.add(new Offer(INFO.code(), INFO.code() + "_" + count, count, instalments, 14,
                    plan.get("interest_pct").asDouble(), instalments.stream().mapToLong(Long::longValue).sum(),
                    plan.get("merchant_fee_cents").asLong(), request.currency()));
        }
        return offers;
    }

    @Override
    public ProviderResult createApplication(ApplicationRequest request) {
        Map<String, Object> customer = new java.util.LinkedHashMap<>();
        customer.put("email", request.applicant().email());
        if (request.applicant().name() != null) {
            customer.put("name", request.applicant().name());
        }
        JsonNode body = ProviderHttp.call(INFO.code(), () -> http.post().uri("/checkouts")
                .body(Map.of("amount_cents", request.amount(), "currency", request.currency(),
                        "instalments", request.instalmentCount(), "customer", customer, "reference", request.reference()))
                .retrieve().body(JsonNode.class));
        return result(body);
    }

    @Override
    public ProviderResult status(String providerRef) {
        return result(ProviderHttp.call(INFO.code(), () -> http.get().uri("/checkouts/{id}", providerRef)
                .retrieve().body(JsonNode.class)));
    }

    @Override
    public void cancel(String providerRef) {
        ProviderHttp.call(INFO.code(), () -> http.post().uri("/checkouts/{id}/cancel", providerRef).retrieve().toBodilessEntity());
    }

    private static ProviderResult result(JsonNode body) {
        if (body == null || !body.has("id") || !body.has("state")) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "no checkout in the answer");
        }
        String id = body.get("id").asString();
        return switch (body.get("state").asString()) {
            case "approved" -> ProviderResult.approved(id);
            case "declined" -> ProviderResult.declined(id, body.path("decline_reason").asString("declined"));
            case "canceled" -> ProviderResult.canceled(id);
            default -> throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "unknown state " + body.get("state").asString());
        };
    }
}
