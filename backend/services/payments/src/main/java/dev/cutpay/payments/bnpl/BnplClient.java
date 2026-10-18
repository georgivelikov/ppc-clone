package dev.cutpay.payments.bnpl;

import dev.cutpay.payments.acquirer.AcquirerClient.RefundResult;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Asks the BNPL aggregator to give back part of a payment it financed. The provider has to agree to it, so a refund of
 * such a payment goes through here and not through the card network. Calls go straight to the service, not the gateway.
 */
@Component
public class BnplClient {

    private static final Logger log = LoggerFactory.getLogger(BnplClient.class);

    record RefundRequest(String refundId, long amount) {
    }

    private final RestClient http;

    public BnplClient(@Value("${cutpay.bnpl-url:http://localhost:8090}") String url, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        // The provider is asked to agree, which can take a moment.
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.http = builder.baseUrl(url).requestFactory(factory).build();
    }

    /** The refund is idempotent on {@code refundId}: asking again for the same one never refunds twice. */
    public RefundResult refund(String applicationId, String refundId, long amount) {
        try {
            http.post().uri("/internal/applications/{id}/refunds", applicationId)
                    .body(new RefundRequest(refundId, amount)).retrieve().toBodilessEntity();
            return new RefundResult.Succeeded(applicationId);
        } catch (HttpClientErrorException e) {
            log.info("The provider refused to refund {} of {}: {}", refundId, applicationId, e.getResponseBodyAsString());
            return new RefundResult.Failed("refund_rejected");
        } catch (RuntimeException e) {
            log.warn("Could not ask the BNPL service to refund {}: {}", refundId, e.toString());
            return new RefundResult.Failed("bnpl_unavailable");
        }
    }
}
