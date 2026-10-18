package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.QuoteRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** KreditKo speaks camelCase JSON in minor units and prices one term per call, so the terms are asked for together. */
@Component
class KreditKoAdapter implements BnplProviderAdapter {

    private static final ProviderInfo INFO = ProviderCatalog.find(ProviderCatalog.KREDITKO).orElseThrow();

    private final RestClient http;
    private final List<Integer> terms;

    KreditKoAdapter(BnplProperties properties, RestClient.Builder builder) {
        this.http = ProviderHttp.client(builder, properties.providers().kreditkoUrl(), properties.providerTimeout());
        this.terms = properties.kreditkoTerms();
    }

    @Override
    public ProviderInfo info() {
        return INFO;
    }

    @Override
    public List<Offer> quote(QuoteRequest request) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Offer>> pending = terms.stream()
                    .map(term -> CompletableFuture.supplyAsync(() -> price(request, term), executor)).toList();
            return pending.stream().map(CompletableFuture::join).toList();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ProviderException provider) {
                throw provider;
            }
            throw new ProviderException(INFO.code(), Kind.UNAVAILABLE, "could not price the terms", e);
        }
    }

    private Offer price(QuoteRequest request, int term) {
        JsonNode body = ProviderHttp.call(INFO.code(), () -> http.post().uri("/simulations")
                .body(Map.of("amountMinor", request.amount(), "termMonths", term)).retrieve().body(JsonNode.class));
        if (body == null || !body.has("monthlyMinor")) {
            throw new ProviderException(INFO.code(), Kind.INVALID_RESPONSE, "no monthly amount in the simulation");
        }
        return new Offer(INFO.code(), INFO.code() + "_" + term, term,
                Collections.nCopies(term, body.get("monthlyMinor").asLong()), 30, body.get("aprPercent").asDouble(),
                body.get("totalPayableMinor").asLong(), body.get("feeMinor").asLong(), request.currency());
    }
}
