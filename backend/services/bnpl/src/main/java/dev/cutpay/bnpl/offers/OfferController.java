package dev.cutpay.bnpl.offers;

import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.offers.OfferService.Offers;
import dev.cutpay.bnpl.routing.Decision.Ranked;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The customer at the checkout asking how they could pay in instalments. Authorised by knowing the client secret. */
@RestController
class OfferController {

    /** What the customer is shown. The merchant fee and the ranking are not for them. */
    record OfferView(String provider, String providerName, String plan, int instalments, List<Long> instalmentAmounts,
                     int intervalDays, double apr, long totalPayable, long customerCost) {
    }

    record Recommended(String provider, String plan) {
    }

    record Unavailable(String provider, String name, String reason) {
    }

    record OffersView(String object, String payment, long amount, String currency, String country, String routingMode,
                      List<OfferView> offers, Recommended recommended, List<Unavailable> unavailable) {
    }

    private final OfferService service;

    OfferController(OfferService service) {
        this.service = service;
    }

    @GetMapping("/checkout/{clientSecret}/bnpl/offers")
    OffersView offers(@PathVariable String clientSecret, @RequestParam(required = false) String country) {
        Offers result = service.offers(clientSecret, country);
        long price = result.payment().amount();
        List<OfferView> offers = new ArrayList<>();
        for (Ranked ranked : result.decision().ranked()) {
            for (Offer offer : ranked.offers()) {
                offers.add(new OfferView(offer.provider(), ranked.name(), offer.plan(), offer.instalmentCount(),
                        offer.instalments(), offer.intervalDays(), offer.aprPercent(), offer.totalPayable(),
                        offer.customerCost(price)));
            }
        }
        Recommended recommended = result.decision().ranked().isEmpty() ? null
                : new Recommended(result.decision().ranked().get(0).provider(), result.decision().ranked().get(0).best().plan());
        // The customer is told which providers could not be reached, not which were turned off or are not suitable.
        List<Unavailable> unavailable = result.decision().excluded().stream()
                .filter(e -> "no_quote".equals(e.code()))
                .map(e -> new Unavailable(e.provider(), dev.cutpay.bnpl.model.ProviderCatalog.find(e.provider()).orElseThrow().name(), e.message()))
                .toList();
        return new OffersView("bnpl_offers", result.payment().paymentId(), price, result.payment().currency(),
                result.country(), result.decision().mode().wire(), offers, recommended, unavailable);
    }
}
