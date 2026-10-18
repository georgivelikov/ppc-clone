package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.config.BnplProperties;
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
}
