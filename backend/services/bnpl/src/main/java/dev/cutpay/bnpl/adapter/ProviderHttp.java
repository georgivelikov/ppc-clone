package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** What every adapter needs: a client with timeouts, and one place that says what each kind of failure means. */
final class ProviderHttp {

    private ProviderHttp() {
    }

    static RestClient client(RestClient.Builder builder, String baseUrl, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(timeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Runs a call to the provider and turns anything that goes wrong into a {@link ProviderException}. */
    static <T> T call(String provider, Supplier<T> call) {
        try {
            return call.get();
        } catch (ProviderException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 504 || status.value() == 408) {
                throw new ProviderException(provider, Kind.TIMEOUT, "timed out", e);
            }
            if (status.is5xxServerError()) {
                throw new ProviderException(provider, Kind.UNAVAILABLE, "answered " + status.value(), e);
            }
            throw new ProviderException(provider, Kind.REFUSED,
                    "answered " + status.value() + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            boolean timedOut = e.getCause() instanceof SocketTimeoutException || e.getCause() instanceof HttpTimeoutException;
            throw new ProviderException(provider, timedOut ? Kind.TIMEOUT : Kind.UNAVAILABLE,
                    timedOut ? "timed out" : "unreachable", e);
        } catch (RestClientException e) {
            throw new ProviderException(provider, Kind.INVALID_RESPONSE, "unreadable answer", e);
        }
    }
}
