package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Money;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.QuoteRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Lendo writes amounts as decimal strings in euros and offers monthly loans; one monthly figure stands for every month. */
@Component
class LendoAdapter implements BnplProviderAdapter {

    private static final ProviderInfo INFO = ProviderCatalog.find(ProviderCatalog.LENDO).orElseThrow();

    private final RestClient http;

    LendoAdapter(BnplProperties properties, RestClient.Builder builder) {
        this.http = ProviderHttp.client(builder, properties.providers().lendoUrl(), properties.providerTimeout());
    }

    @Override
    public ProviderInfo info() {
        return INFO;
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
}
