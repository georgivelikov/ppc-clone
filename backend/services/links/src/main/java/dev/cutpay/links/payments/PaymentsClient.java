package dev.cutpay.links.payments;

import dev.cutpay.links.config.LinkProperties;
import dev.cutpay.web.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Starts a payment for a customer who opened a link. Calls the payments service directly, not via the gateway. */
@Component
public class PaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentsClient.class);

    public record Created(String paymentId, String clientSecret, String url) {
    }

    record Payment(long amount, String currency, String description, String customerEmail,
                   Map<String, String> metadata, String returnUrl) {
    }

    record CreateForMerchant(String merchantId, boolean livemode, String merchantName, Payment payment) {
    }

    private final RestClient http;

    public PaymentsClient(LinkProperties properties, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.baseUrl(properties.paymentsUrl()).requestFactory(factory).build();
    }

    public Created create(String merchantId, boolean livemode, String merchantName, long amount, String currency,
                          String description, String customerEmail, Map<String, String> metadata, String returnUrl) {
        try {
            Created created = http.post().uri("/internal/payments")
                    .body(new CreateForMerchant(merchantId, livemode, merchantName == null ? "" : merchantName,
                            new Payment(amount, currency, description, customerEmail, metadata, returnUrl)))
                    .retrieve().body(Created.class);
            if (created == null) {
                throw unavailable();
            }
            return created;
        } catch (RestClientException e) {
            log.warn("Payments service call failed: {}", e.toString());
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "api_error", "payments_unavailable",
                "We could not start the payment, please try again", null);
    }
}
