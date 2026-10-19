package dev.cutpay.kycsim;

import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Three services in one pretend provider.
 *
 * <ul>
 *   <li><b>Company registry</b>: {@code GET /registry/{country}/{number}} answers with a made-up company for any number in the
 *       country's format. A number ending in {@code 000} is not in the register and one ending in {@code 999} belongs to a
 *       dissolved company.</li>
 *   <li><b>Document verification</b>: {@code POST /verifications} starts a check and {@code GET /verifications/{id}} has the
 *       answer after a few seconds. A document number ending in {@code 0} is rejected, one ending in {@code 9} needs a
 *       better photo, anything else is approved.</li>
 *   <li><b>Screening</b>: {@code POST /screening} looks a name up in a tiny made-up list of sanctioned and politically exposed
 *       people.</li>
 * </ul>
 */
@RestController
class KycController {

    /** The register number formats, by country. */
    private static final Map<String, Pattern> REGISTER = Map.of(
            "BG", Pattern.compile("^([0-9]{9}|[0-9]{13})$"), // EIK / BULSTAT
            "RO", Pattern.compile("^[0-9]{2,10}$"),          // CUI
            "PL", Pattern.compile("^[0-9]{10}$"),            // KRS
            "GR", Pattern.compile("^[0-9]{12}$"),            // GEMI
            "HU", Pattern.compile("^[0-9]{2}-[0-9]{2}-[0-9]{6}$"), // Cegjegyzekszam
            "CZ", Pattern.compile("^[0-9]{8}$"),             // ICO
            "DE", Pattern.compile("^HRB ?[0-9]{3,6}$"),
            "IE", Pattern.compile("^[0-9]{5,6}$"),           // CRO number
            "GB", Pattern.compile("^[0-9A-Z]{8}$"));         // Companies House

    private static final List<String> SANCTIONED = List.of("ivan sanctionov", "boris blocked");
    private static final List<String> POLITICALLY_EXPOSED = List.of("petar politicov", "maria minister");

    private static final List<String> FIRST = List.of("Ivan", "Maria", "Georgi", "Elena", "Nikolay", "Desislava", "Stefan", "Ana");
    private static final List<String> LAST = List.of("Petrov", "Ivanova", "Georgiev", "Dimitrova", "Kolev", "Stoyanova", "Hristov", "Todorova");
    private static final List<String> TRADES = List.of("Trading", "Consulting", "Foods", "Logistics", "Studio", "Retail", "Services", "Tech");
    private static final List<String> CITIES = Map.of("BG", "Sofia", "RO", "Bucharest", "PL", "Warsaw", "GR", "Athens", "HU", "Budapest",
            "CZ", "Prague", "DE", "Berlin", "IE", "Dublin", "GB", "London").values().stream().sorted().toList();

    record Address(String line1, String city, String postalCode) {
    }

    record Representative(String name, String role) {
    }

    record Company(String registrationNumber, String country, String name, String legalForm, String status,
                   LocalDate incorporatedOn, Address address, List<Representative> representatives) {
    }

    record VerificationRequest(@NotBlank String documentNumber, @NotBlank String documentType, @NotBlank String fullName,
                               String dateOfBirth, String country) {
    }

    record Verification(String id, String status, String reason) {
    }

    record ScreeningRequest(@NotBlank String fullName, String country) {
    }

    record Hit(String list, String name) {
    }

    record ScreeningResult(String fullName, List<Hit> hits) {
    }

    private record Started(VerificationRequest request, Instant at) {
    }

    private final Map<String, Started> verifications = new ConcurrentHashMap<>();
    private final Duration delay;

    KycController(@Value("${cutpay.kyc-sim.verification-delay:PT3S}") Duration delay) {
        this.delay = delay;
    }

    @GetMapping("/registry/{country}/{number}")
    Company company(@PathVariable String country, @PathVariable String number) {
        String code = country.toUpperCase(Locale.ROOT);
        Pattern format = REGISTER.get(code);
        if (format == null) {
            throw ApiException.notFound("unsupported_country", "The register of " + code + " is not available");
        }
        String clean = number.trim().toUpperCase(Locale.ROOT);
        if (!format.matcher(clean).matches()) {
            throw ApiException.invalidRequest("invalid_registration_number",
                    "That is not a registration number in the format of " + code, "registration_number");
        }
        if (clean.endsWith("000")) {
            throw ApiException.notFound("company_not_found", "No company with that number is in the register");
        }
        int seed = Math.abs(clean.hashCode());
        String owner = FIRST.get(seed % FIRST.size()) + " " + LAST.get((seed / 7) % LAST.size());
        String second = FIRST.get((seed / 3) % FIRST.size()) + " " + LAST.get((seed / 11) % LAST.size());
        String trade = TRADES.get((seed / 5) % TRADES.size());
        return new Company(clean, code, LAST.get((seed / 13) % LAST.size()) + " " + trade + (code.equals("BG") ? " OOD" : " Ltd"),
                code.equals("BG") ? "OOD" : "Ltd", clean.endsWith("999") ? "dissolved" : "active",
                LocalDate.of(2005 + seed % 18, 1 + seed % 12, 1 + seed % 27),
                new Address((1 + seed % 120) + " Vitosha Blvd", CITIES.get(seed % CITIES.size()), String.valueOf(1000 + seed % 8999)),
                List.of(new Representative(owner, "manager"), new Representative(second, "director")));
    }

    @PostMapping("/verifications")
    @ResponseStatus(HttpStatus.CREATED)
    Verification start(@Valid @RequestBody VerificationRequest request) {
        String id = "vrf_" + UUID.randomUUID().toString().replace("-", "");
        verifications.put(id, new Started(request, Instant.now()));
        return new Verification(id, "pending", null);
    }

    @GetMapping("/verifications/{id}")
    Verification result(@PathVariable String id) {
        Started started = verifications.get(id);
        if (started == null) {
            throw ApiException.notFound("verification_not_found", "No such verification");
        }
        if (Instant.now().isBefore(started.at().plus(delay))) {
            return new Verification(id, "pending", null);
        }
        String number = started.request().documentNumber().trim();
        char last = number.charAt(number.length() - 1);
        if (last == '0') {
            return new Verification(id, "rejected", "The document could not be verified as genuine.");
        }
        if (last == '9') {
            return new Verification(id, "needs_more_info", "The photo of the document is not clear enough. Upload a sharper one.");
        }
        return new Verification(id, "approved", null);
    }

    @PostMapping("/screening")
    ScreeningResult screen(@Valid @RequestBody ScreeningRequest request) {
        String name = request.fullName().trim().toLowerCase(Locale.ROOT);
        List<Hit> hits = new java.util.ArrayList<>();
        if (SANCTIONED.contains(name)) {
            hits.add(new Hit("sanctions", request.fullName().trim()));
        }
        if (POLITICALLY_EXPOSED.contains(name)) {
            hits.add(new Hit("pep", request.fullName().trim()));
        }
        return new ScreeningResult(request.fullName().trim(), hits);
    }

    /** The countries whose registers this pretend provider can search. */
    @GetMapping("/registry")
    Set<String> countries() {
        return REGISTER.keySet();
    }
}
