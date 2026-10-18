package dev.cutpay.billing.payments;

import dev.cutpay.billing.config.BillingProperties;
import dev.cutpay.web.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to the payments service, which owns customers, saved cards and every payment. Calls go straight to it, not
 * through the gateway.
 */
@Component
public class PaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentsClient.class);

    public record Customer(String id, boolean livemode, String email, String name, String defaultPaymentMethod) {
    }

    /** {@code status} says how the payment ended: an off-session charge is finished before this is returned. */
    public record Charge(String paymentId, String clientSecret, String url, String status, String failureCode,
                         String failureMessage) {
        public boolean succeeded() {
            return "succeeded".equals(status);
        }
    }

    record Payment(long amount, String currency, String description, String customerEmail,
                   Map<String, String> metadata, String returnUrl, String customer, String paymentMethod,
                   Boolean offSession) {
    }

    record CreateForMerchant(String merchantId, boolean livemode, String merchantName, Payment payment) {
    }

    private final RestClient http;

    public PaymentsClient(BillingProperties properties, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        // An off-session charge waits for the card network, so allow longer than a lookup.
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = builder.baseUrl(properties.paymentsUrl()).requestFactory(factory).build();
    }

    /** The customer of a merchant, or a 404 {@link ApiException} when there is none. */
    public Customer customer(String merchantId, String customerId) {
        try {
            Customer customer = http.get().uri("/internal/merchants/{m}/customers/{c}", merchantId, customerId)
                    .retrieve().body(Customer.class);
            if (customer == null) {
                throw unavailable();
            }
            return customer;
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("customer_not_found", "No such customer");
        } catch (RestClientException e) {
            log.warn("Payments service call failed: {}", e.toString());
            throw unavailable();
        }
    }

    /**
     * Charges the saved card of the customer without them being there. A payment the card network refuses is not an
     * error here: it comes back with {@code status = failed} and a code.
     */
    public Charge chargeOffSession(String merchantId, boolean livemode, String merchantName, String customerId,
                                   String paymentMethod, long amount, String currency, String description,
                                   Map<String, String> metadata) {
        return create(merchantId, livemode, merchantName,
                new Payment(amount, currency, description, null, metadata, null, customerId, paymentMethod, true));
    }

    /** A payment the customer completes on the hosted checkout, for an invoice paid from its page. */
    public Charge createCheckout(String merchantId, boolean livemode, String merchantName, String customerId,
                                 String email, long amount, String currency, String description,
                                 Map<String, String> metadata, String returnUrl) {
        return create(merchantId, livemode, merchantName,
                new Payment(amount, currency, description, email, metadata, returnUrl, customerId, null, null));
    }

    private Charge create(String merchantId, boolean livemode, String merchantName, Payment payment) {
        try {
            Charge charge = http.post().uri("/internal/payments")
                    .body(new CreateForMerchant(merchantId, livemode, merchantName == null ? "" : merchantName, payment))
                    .retrieve().body(Charge.class);
            if (charge == null) {
                throw unavailable();
            }
            return charge;
        } catch (HttpClientErrorException e) {
            // The payments service refused the request itself, for example a customer without a saved card.
            log.warn("Payments service refused a payment: {}", e.getResponseBodyAsString());
            throw ApiException.invalidRequest("payment_rejected", refusal(e), null);
        } catch (RestClientException e) {
            log.warn("Payments service call failed: {}", e.toString());
            throw unavailable();
        }
    }

    private static String refusal(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body.contains("no_payment_method")) {
            return "The customer has no saved card to charge";
        }
        if (body.contains("payment_method_not_found")) {
            return "The saved card is no longer available";
        }
        return "The payment could not be created";
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "api_error", "payments_unavailable",
                "The payments service is unavailable, please try again", null);
    }
}
