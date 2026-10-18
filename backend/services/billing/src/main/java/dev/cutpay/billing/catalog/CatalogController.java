package dev.cutpay.billing.catalog;

import dev.cutpay.billing.catalog.CatalogService.NewPrice;
import dev.cutpay.billing.catalog.CatalogService.NewProduct;
import dev.cutpay.billing.catalog.CatalogService.Price;
import dev.cutpay.billing.catalog.CatalogService.Product;
import dev.cutpay.billing.catalog.CatalogService.ProductChanges;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogController {

    record CreateProduct(@NotBlank @Size(max = 160) String name, @Size(max = 1000) String description,
                         @Size(max = 2000) String imageUrl, Map<String, String> metadata) {
    }

    record UpdateProduct(@Size(max = 160) String name, @Size(max = 1000) String description,
                         @Size(max = 2000) String imageUrl, Boolean active, Map<String, String> metadata) {
    }

    record ProductView(String id, String object, boolean livemode, String name, String description, String imageUrl,
                       boolean active, Map<String, String> metadata, Instant createdAt) {
        static ProductView of(Product p) {
            return new ProductView(p.id(), "product", p.livemode(), p.name(), p.description(), p.imageUrl(), p.active(),
                    p.metadata(), p.createdAt());
        }
    }

    record ProductList(String object, List<ProductView> data, boolean hasMore) {
    }

    record CreatePrice(@NotBlank String product, @Min(1) long unitAmount, @NotBlank String currency, String interval,
                       Integer intervalCount, Integer trialDays, @Size(max = 160) String nickname) {
    }

    record UpdatePrice(Boolean active, @Size(max = 160) String nickname) {
    }

    record PriceView(String id, String object, boolean livemode, String product, long unitAmount, String currency,
                     String type, String interval, int intervalCount, int trialDays, String nickname, boolean active,
                     Instant createdAt) {
        static PriceView of(Price p) {
            return new PriceView(p.id(), "price", p.livemode(), p.productId(), p.unitAmount(), p.currency(), p.type(),
                    p.interval() == null ? null : p.interval().wire(), p.intervalCount(), p.trialDays(), p.nickname(),
                    p.active(), p.createdAt());
        }
    }

    record PriceList(String object, List<PriceView> data, boolean hasMore) {
    }

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/v1/products")
    @ResponseStatus(HttpStatus.CREATED)
    ProductView createProduct(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                              @Valid @RequestBody CreateProduct request) {
        return ProductView.of(catalog.createProduct(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode),
                new NewProduct(request.name(), request.description(), request.imageUrl(), request.metadata())));
    }

    @GetMapping("/v1/products")
    ProductList products(@AuthenticationPrincipal Jwt jwt,
                         @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                         @RequestParam(required = false) Boolean active,
                         @RequestParam(name = "starting_after", required = false) String startingAfter,
                         @RequestParam(defaultValue = "20") int limit) {
        var page = catalog.products(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), active,
                startingAfter, limit);
        return new ProductList("list", page.data().stream().map(ProductView::of).toList(), page.hasMore());
    }

    @GetMapping("/v1/products/{id}")
    ProductView product(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ProductView.of(catalog.product(merchant(jwt), claim(jwt), id));
    }

    @PatchMapping("/v1/products/{id}")
    ProductView updateProduct(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                              @Valid @RequestBody UpdateProduct request) {
        return ProductView.of(catalog.updateProduct(merchant(jwt), claim(jwt), id,
                new ProductChanges(request.name(), request.description(), request.imageUrl(), request.active(),
                        request.metadata())));
    }

    @PostMapping("/v1/prices")
    @ResponseStatus(HttpStatus.CREATED)
    PriceView createPrice(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                          @Valid @RequestBody CreatePrice request) {
        return PriceView.of(catalog.createPrice(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode),
                new NewPrice(request.product(), request.unitAmount(), request.currency(), request.interval(),
                        request.intervalCount(), request.trialDays(), request.nickname())));
    }

    @GetMapping("/v1/prices")
    PriceList prices(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                     @RequestParam(required = false) String product, @RequestParam(required = false) Boolean active,
                     @RequestParam(name = "starting_after", required = false) String startingAfter,
                     @RequestParam(defaultValue = "20") int limit) {
        var page = catalog.prices(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), product, active,
                startingAfter, limit);
        return new PriceList("list", page.data().stream().map(PriceView::of).toList(), page.hasMore());
    }

    @GetMapping("/v1/prices/{id}")
    PriceView price(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return PriceView.of(catalog.price(merchant(jwt), claim(jwt), id));
    }

    @PatchMapping("/v1/prices/{id}")
    PriceView updatePrice(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                          @Valid @RequestBody UpdatePrice request) {
        return PriceView.of(catalog.updatePrice(merchant(jwt), claim(jwt), id, request.active(), request.nickname()));
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    private static Boolean claim(Jwt jwt) {
        return jwt.getClaimAsBoolean("livemode");
    }
}
