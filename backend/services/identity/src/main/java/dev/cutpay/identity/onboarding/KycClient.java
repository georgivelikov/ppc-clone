package dev.cutpay.identity.onboarding;

import dev.cutpay.web.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** The pretend identity-verification provider and company registry (backend/services/kyc-sim). */
@Component
public class KycClient {

    public record Address(String line1, String city, String postalCode) {
    }

    public record Representative(String name, String role) {
    }

    public record Company(String registrationNumber, String country, String name, String legalForm, String status,
                          LocalDate incorporatedOn, Address address, List<Representative> representatives) {
    }

    public record Verification(String id, String status, String reason) {
    }

    public record Hit(String list, String name) {
    }

    public record Screening(String fullName, List<Hit> hits) {
    }

    record VerificationRequest(String documentNumber, String documentType, String fullName, String dateOfBirth, String country) {
    }

    record ScreeningRequest(String fullName, String country) {
    }

    private final RestClient http;

    public KycClient(@Value("${cutpay.kyc.url}") String url, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.baseUrl(url).requestFactory(factory).build();
    }

    /** Empty when the register has no such company; throws a 400 for a number in the wrong format. */
    public Optional<Company> company(String country, String number) {
        try {
            return Optional.ofNullable(http.get().uri("/registry/{c}/{n}", country, number).retrieve().body(Company.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw ApiException.invalidRequest("invalid_registration_number",
                    "That is not a registration number in the format of " + country, "registration_number");
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    public Verification startVerification(String documentNumber, String documentType, String fullName, LocalDate dateOfBirth,
                                          String country) {
        try {
            return http.post().uri("/verifications")
                    .body(new VerificationRequest(documentNumber, documentType, fullName, dateOfBirth.toString(), country))
                    .retrieve().body(Verification.class);
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    /** Empty when the provider cannot be reached right now, so the caller asks again later. */
    public Optional<Verification> verification(String id) {
        try {
            return Optional.ofNullable(http.get().uri("/verifications/{id}", id).retrieve().body(Verification.class));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    public Screening screen(String fullName, String country) {
        try {
            return http.post().uri("/screening").body(new ScreeningRequest(fullName, country)).retrieve().body(Screening.class);
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "api_error", "kyc_unavailable",
                "The identity checks are unavailable right now. Please try again in a moment.", null);
    }
}
