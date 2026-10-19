package dev.cutpay.ledger.fx;

import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Exchange rates, locked quotes, conversions between balances and the settlement settings. */
@RestController
class FxController {

    private static final int MAX_PAGE = 100;

    record RatesView(String object, String base, Instant asOf, Map<String, BigDecimal> rates) {
    }

    record QuoteRequest(@NotBlank String fromCurrency, @NotBlank String toCurrency, @Min(1) long amount) {
    }

    record QuoteView(String id, String object, String fromCurrency, String toCurrency, long fromAmount, long midAmount,
                     long toAmount, long markupAmount, BigDecimal rate, int markupBps, Instant expiresAt,
                     boolean livemode) {
    }

    record ConvertRequest(@NotBlank String fromCurrency, @NotBlank String toCurrency, @Min(1) long amount, String quoteId) {
    }

    record ConversionView(String id, String object, String fromCurrency, String toCurrency, long fromAmount, long midAmount,
                          long toAmount, long markupAmount, BigDecimal rate, int markupBps, String kind, String quoteId,
                          String sourceId, Instant created) {
    }

    record ConversionList(String object, List<ConversionView> data, boolean hasMore) {
    }

    record ReportView(String object, int days, List<FxService.ReportRow> data) {
    }

    record SettingsView(String object, List<String> settlementCurrencies, boolean autoConvert, int fxMarkupBps) {
    }

    record SettingsRequest(@NotNull @Size(min = 1, max = 8) List<String> settlementCurrencies, Boolean autoConvert) {
    }

    private final FxService fx;
    private final Clock clock;

    FxController(FxService fx, Clock clock) {
        this.fx = fx;
        this.clock = clock;
    }

    /** What one unit of the base currency buys of each other currency. */
    @GetMapping("/v1/fx/rates")
    RatesView rates(@RequestParam(defaultValue = "EUR") String base) {
        String from = base.toUpperCase(Locale.ROOT);
        RateBook.Snapshot snapshot = fx.snapshot();
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (String code : snapshot.perEuro().keySet()) {
            if (!code.equals(from)) {
                rates.put(code, snapshot.rate(from, code).stripTrailingZeros());
            }
        }
        return new RatesView("fx_rates", from, snapshot.asOf(), rates);
    }

    @PostMapping("/v1/fx/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    QuoteView quote(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                    @Valid @RequestBody QuoteRequest request) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        FxService.Quote q = fx.quote(merchant(jwt), livemode, request.fromCurrency(), request.toCurrency(), request.amount());
        return new QuoteView(q.id(), "fx_quote", q.fromCurrency(), q.toCurrency(), q.fromAmount(), q.midAmount(), q.toAmount(),
                q.markupAmount(), q.rate(), q.markupBps(), q.expiresAt(), q.livemode());
    }

    /** Moves available money from one currency to another. */
    @PostMapping("/v1/balance/convert")
    @ResponseStatus(HttpStatus.CREATED)
    ConversionView convert(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                           @Valid @RequestBody ConvertRequest request) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        return view(fx.convert(merchant(jwt), livemode, request.fromCurrency(), request.toCurrency(), request.amount(),
                request.quoteId()));
    }

    @GetMapping("/v1/fx/conversions")
    ConversionList conversions(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                               @RequestParam(name = "starting_after", required = false) String startingAfter,
                               @RequestParam(defaultValue = "20") int limit) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalidRequest("invalid_limit", "limit must be between 1 and " + MAX_PAGE, "limit");
        }
        List<FxService.Conversion> rows = fx.conversions(merchant(jwt), livemode, startingAfter, limit + 1);
        boolean hasMore = rows.size() > limit;
        return new ConversionList("list", rows.stream().limit(limit).map(FxController::view).toList(), hasMore);
    }

    @GetMapping("/v1/fx/report")
    ReportView report(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                      @RequestParam(defaultValue = "30") int days) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        if (days < 1 || days > 365) {
            throw ApiException.invalidRequest("invalid_days", "days must be between 1 and 365", "days");
        }
        return new ReportView("fx_report", days,
                fx.report(merchant(jwt), livemode, clock.instant().minus(Duration.ofDays(days))));
    }

    @GetMapping("/v1/currency_settings")
    SettingsView settings(@AuthenticationPrincipal Jwt jwt) {
        return fx.settings(merchant(jwt))
                .map(s -> new SettingsView("currency_settings", s.settlementCurrencies(), s.autoConvert(), s.fxMarkupBps()))
                .orElseGet(() -> new SettingsView("currency_settings", List.of(), false, FxService.DEFAULT_MARKUP_BPS));
    }

    @PutMapping("/v1/currency_settings")
    SettingsView updateSettings(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SettingsRequest request) {
        FxService.Settings s = fx.updateSettings(merchant(jwt), request.settlementCurrencies(),
                Boolean.TRUE.equals(request.autoConvert()));
        return new SettingsView("currency_settings", s.settlementCurrencies(), s.autoConvert(), s.fxMarkupBps());
    }

    private static ConversionView view(FxService.Conversion c) {
        return new ConversionView(c.id(), "fx_conversion", c.fromCurrency(), c.toCurrency(), c.fromAmount(), c.midAmount(),
                c.toAmount(), c.markupAmount(), c.rate(), c.markupBps(), c.kind(), c.quoteId(), c.sourceId(), c.createdAt());
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }
}
