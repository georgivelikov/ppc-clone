package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.config.BnplProperties;
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
}
