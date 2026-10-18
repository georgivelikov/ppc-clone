package dev.cutpay.bnpl.payments;

import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.web.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Talks to the payments service, which owns every payment. Calls go straight to it, not through the gateway. */
@Component
public class PaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentsClient.class);

    /** What the aggregator needs to know about the payment a customer is about to pay in instalments. */
    public record CheckoutFacts(String paymentId, String merchantId, boolean livemode, String merchantName, String status,
                                long amount, String currency, String description, String customerEmail, String captureMethod,
                                boolean split) {

        /** Pay in instalments only applies to a payment nobody has paid yet and that is taken straight away. */
        public void requireBnplPayable() {
            if (!"requires_payment_method".equals(status)) {
                throw ApiException.conflict("payment_not_payable", "This payment can no longer be paid", null);
            }
            if (split) {
                throw ApiException.conflict("bnpl_not_available", "Pay in instalments is not available for a split payment", null);
            }
            if (!"automatic".equals(captureMethod)) {
                throw ApiException.conflict("bnpl_not_available", "Pay in instalments is not available for a payment that is held", null);
            }
        }
    }

    private final RestClient http;

    public PaymentsClient(BnplProperties properties, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.baseUrl(properties.paymentsUrl()).requestFactory(factory).build();
    }

    public CheckoutFacts checkout(String clientSecret) {
        try {
            CheckoutFacts facts = http.get().uri("/internal/checkout/{secret}", clientSecret).retrieve().body(CheckoutFacts.class);
            if (facts == null) {
                throw unavailable();
            }
            return facts;
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("session_not_found", "This payment link is not valid");
        } catch (RestClientException e) {
            log.warn("Payments service call failed: {}", e.toString());
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "api_error", "payments_unavailable", "Payments is not reachable right now", null);
    }
}
