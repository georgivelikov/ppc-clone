package dev.cutpay.bnpl.offers;

import dev.cutpay.bnpl.adapter.Adapters;
import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.QuoteRequest;
import dev.cutpay.bnpl.offers.DecisionRepository.Stored;
import dev.cutpay.bnpl.offers.Quoter.Quotes;
import dev.cutpay.bnpl.payments.PaymentsClient;
import dev.cutpay.bnpl.payments.PaymentsClient.CheckoutFacts;
import dev.cutpay.bnpl.routing.Decision;
import dev.cutpay.bnpl.routing.Eligibility;
import dev.cutpay.bnpl.routing.MerchantRouting;
import dev.cutpay.bnpl.routing.RoutingEngine;
import dev.cutpay.bnpl.routing.StatsSource;
import dev.cutpay.bnpl.settings.SettingsService;
import dev.cutpay.web.Ids;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the checkout shows under "Pay in instalments": work out which providers may be asked, ask them all at once, rank
 * the answers by the merchant's routing mode, and keep the reasoning so it can be explained afterwards.
 */
@Service
public class OfferService {

    /** Everything a response needs: the payment as the checkout sees it, and what the engine decided. */
    public record Offers(CheckoutFacts payment, String country, Decision decision, Quotes quotes) {
    }

    private final PaymentsClient payments;
    private final SettingsService settings;
    private final Adapters adapters;
    private final Quoter quoter;
    private final StatsSource stats;
    private final DecisionRepository decisions;
    private final BnplProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    public OfferService(PaymentsClient payments, SettingsService settings, Adapters adapters, Quoter quoter, StatsSource stats,
                        DecisionRepository decisions, BnplProperties properties, JsonMapper json, Clock clock) {
        this.payments = payments;
        this.settings = settings;
        this.adapters = adapters;
        this.quoter = quoter;
        this.stats = stats;
        this.decisions = decisions;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public Offers offers(String clientSecret, String requestedCountry) {
        CheckoutFacts payment = payments.checkout(clientSecret);
        payment.requireBnplPayable();
        String country = requestedCountry == null || requestedCountry.isBlank() ? properties.defaultCountry()
                : requestedCountry.trim().toUpperCase(Locale.ROOT);
        MerchantRouting routing = settings.get(payment.merchantId(), payment.livemode());

        Eligibility eligibility = RoutingEngine.eligible(payment.amount(), payment.currency(), country, routing, adapters.unavailable());
        Quotes quotes = eligibility.eligible().isEmpty() ? new Quotes(Map.of(), Map.of())
                : quoter.quote(eligibility.eligible(), new QuoteRequest(payment.amount(), payment.currency(), country));
        Decision decision = RoutingEngine.decide(routing, payment.amount(), payment.currency(), eligibility, quotes.offers(),
                quotes.failures(), stats::stats);

        decisions.insert(new Stored(Ids.generate("rd"), payment.paymentId(), decision.mode().wire(), payment.amount(),
                payment.currency(), country, decision.chosen(), decision.reason(), json.writeValueAsString(detail(decision)),
                clock.instant()), payment.merchantId(), payment.livemode());
        return new Offers(payment, country, decision, quotes);
    }

    /** The stored form of a decision: what was ranked and what was left out. */
    record Detail(List<Decision.Ranked> ranked, List<Eligibility.Exclusion> excluded) {
    }

    private static Detail detail(Decision decision) {
        return new Detail(decision.ranked(), decision.excluded());
    }

}
