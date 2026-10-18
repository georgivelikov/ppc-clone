package dev.cutpay.bnplsim;

import static dev.cutpay.bnplsim.Providers.PAYLATEREU;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayLaterEU: pay the whole amount after 30 days, 2.5% for the merchant, EUR 20 to 800. One RPC-style endpoint that
 * takes an {@code action} and always answers 200 with {@code ok} and a {@code code}, even when it refuses. Amounts are
 * JSON numbers in euros. It is the flaky one: a share of calls hang and then time out.
 */
@RestController
class PayLaterEuController implements Resettable {

    static final BigDecimal MIN = new BigDecimal("20.00");
    static final BigDecimal MAX = new BigDecimal("800.00");
    static final int FEE_BPS = 250;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Call(@JsonProperty("action") String action, @JsonProperty("amount") BigDecimal amount,
                @JsonProperty("currency") String currency, @JsonProperty("customerEmail") String customerEmail,
                @JsonProperty("reference") String reference, @JsonProperty("authId") String authId) {
    }

    private static final class Authorisation {
        final String id = "PL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String state;
        BigDecimal amount;
        BigDecimal refunded = BigDecimal.ZERO;
        String reference;
    }

    private final Map<String, Authorisation> authorisations = new ConcurrentHashMap<>();
    private final Providers providers;

    PayLaterEuController(Providers providers) {
        this.providers = providers;
    }

    @Override
    public void reset() {
        authorisations.clear();
    }

    @PostMapping("/paylatereu/api")
    Map<String, Object> call(@RequestBody Call call) {
        providers.enter(PAYLATEREU);
        if (call.action() == null) {
            return answer(false, "NO_ACTION", null);
        }
        return switch (call.action()) {
            case "quote" -> quote(call);
            case "authorise" -> authorise(call);
            case "status" -> status(call);
            case "cancel" -> cancel(call);
            case "refund" -> refund(call);
            default -> answer(false, "UNKNOWN_ACTION", null);
        };
    }

    private Map<String, Object> quote(Call call) {
        Map<String, Object> refused = refuse(call);
        if (refused != null) {
            return refused;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payInDays", 30);
        data.put("customerCost", BigDecimal.ZERO.setScale(2));
        data.put("merchantFee", fee(call.amount()));
        return answer(true, "OK", data);
    }

    private Map<String, Object> authorise(Call call) {
        Map<String, Object> refused = refuse(call);
        if (refused != null) {
            return refused;
        }
        Authorisation authorisation = new Authorisation();
        authorisation.amount = call.amount().setScale(2, RoundingMode.HALF_UP);
        authorisation.reference = call.reference();
        boolean declined = "debt@example.com".equalsIgnoreCase(call.customerEmail());
        authorisation.state = declined ? "DECLINED" : "AUTHORISED";
        authorisations.put(authorisation.id, authorisation);
        return answer(!declined, declined ? "DECLINED" : "OK", view(authorisation));
    }

    private Map<String, Object> status(Call call) {
        Authorisation authorisation = authorisations.get(call.authId());
        return authorisation == null ? answer(false, "UNKNOWN_AUTH", null) : answer(true, "OK", view(authorisation));
    }

    private Map<String, Object> cancel(Call call) {
        Authorisation authorisation = authorisations.get(call.authId());
        if (authorisation == null) {
            return answer(false, "UNKNOWN_AUTH", null);
        }
        synchronized (authorisation) {
            if (!"AUTHORISED".equals(authorisation.state) || authorisation.refunded.signum() > 0) {
                return answer(false, "NOT_CANCELABLE", view(authorisation));
            }
            authorisation.state = "CANCELED";
            return answer(true, "OK", view(authorisation));
        }
    }

    private Map<String, Object> refund(Call call) {
        Authorisation authorisation = authorisations.get(call.authId());
        if (authorisation == null) {
            return answer(false, "UNKNOWN_AUTH", null);
        }
        synchronized (authorisation) {
            if (!"AUTHORISED".equals(authorisation.state)) {
                return answer(false, "NOT_REFUNDABLE", view(authorisation));
            }
            if (call.amount() == null || call.amount().signum() <= 0
                    || authorisation.refunded.add(call.amount()).compareTo(authorisation.amount) > 0) {
                return answer(false, "REFUND_TOO_LARGE", view(authorisation));
            }
            authorisation.refunded = authorisation.refunded.add(call.amount()).setScale(2, RoundingMode.HALF_UP);
            return answer(true, "OK", view(authorisation));
        }
    }

    private static Map<String, Object> refuse(Call call) {
        if (call.currency() != null && !"EUR".equalsIgnoreCase(call.currency())) {
            return answer(false, "CURRENCY", null);
        }
        if (call.amount() == null || call.amount().compareTo(MIN) < 0 || call.amount().compareTo(MAX) > 0) {
            return answer(false, "AMOUNT_RANGE", null);
        }
        return null;
    }

    private static Map<String, Object> view(Authorisation authorisation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authId", authorisation.id);
        data.put("state", authorisation.state);
        data.put("amount", authorisation.amount);
        data.put("refunded", authorisation.refunded);
        data.put("merchantFee", fee(authorisation.amount));
        data.put("payInDays", 30);
        data.put("reference", authorisation.reference);
        return data;
    }

    private static BigDecimal fee(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(FEE_BPS)).divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> answer(boolean ok, String code, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        body.put("code", code);
        body.put("data", data);
        return body;
    }
}
