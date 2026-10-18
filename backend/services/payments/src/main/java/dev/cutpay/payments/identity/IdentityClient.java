package dev.cutpay.payments.identity;

import dev.cutpay.web.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Asks the identity service about connected accounts before money is sent to them. Not routed through the gateway. */
@Component
public class IdentityClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);

    public record ConnectedAccount(String id, String status, String currency, boolean livemode) {
        public boolean active() {
            return "active".equals(status);
        }
    }

    private final RestClient http;

    public IdentityClient(@Value("${cutpay.identity-url}") String url, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.baseUrl(url).requestFactory(factory).build();
    }

    /** The account of this platform, or an invalid-request error that names the {@code transfer_data} field. */
    public ConnectedAccount connectedAccount(String platformMerchantId, String accountId) {
        try {
            ConnectedAccount account = http.get()
                    .uri("/internal/platforms/{platform}/connected_accounts/{id}", platformMerchantId, accountId)
                    .retrieve().body(ConnectedAccount.class);
            if (account == null) {
                throw unavailable();
            }
            return account;
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.invalidRequest("connected_account_not_found",
                    "No connected account %s on this platform".formatted(accountId), "transfer_data");
        } catch (RestClientException e) {
            log.warn("Identity service call failed: {}", e.toString());
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "api_error", "identity_unavailable",
                "Could not check the connected account, please try again", null);
    }
}
