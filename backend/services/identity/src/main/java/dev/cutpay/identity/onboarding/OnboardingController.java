package dev.cutpay.identity.onboarding;

import dev.cutpay.identity.domain.Repositories.MerchantRepository;
import dev.cutpay.identity.onboarding.KycClient.Company;
import dev.cutpay.identity.onboarding.OnboardingService.Application;
import dev.cutpay.identity.onboarding.OnboardingService.DocumentFile;
import dev.cutpay.identity.onboarding.OnboardingService.DocumentInfo;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The application wizard's API. The gateway lets only owners and admins in; nothing here can be called with an API key. */
@RestController
class OnboardingController {

    record BusinessRequest(@NotBlank String businessType, @NotBlank String industry, String registrationNumber) {
    }

    record ProfileRequest(String website, String description, long expectedMonthlyVolume) {
    }

    record BankRequest(String iban, String accountHolder) {
    }

    record PersonRequest(String role, String name, String dateOfBirth, String nationality, Integer ownershipPct,
                         String documentType, String documentNumber) {
    }

    record SubmitRequest(boolean acceptedTerms, String signedName) {
    }

    private final OnboardingService onboarding;
    private final KycClient kyc;
    private final MerchantRepository merchants;

    OnboardingController(OnboardingService onboarding, KycClient kyc, MerchantRepository merchants) {
        this.onboarding = onboarding;
        this.kyc = kyc;
        this.merchants = merchants;
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    @GetMapping("/v1/onboarding")
    Application get(@AuthenticationPrincipal Jwt jwt) {
        return onboarding.view(merchant(jwt));
    }

    /** What the register says about a number, so the person can check it is theirs before saving. */
    @GetMapping("/v1/onboarding/company")
    Company company(@AuthenticationPrincipal Jwt jwt, @RequestParam("registration_number") String number) {
        String country = merchants.findById(merchant(jwt)).orElseThrow().getCountry();
        return kyc.company(country, number.trim()).orElseThrow(() -> ApiException.notFound("company_not_found",
                "No company with that number is in the register of " + country));
    }

    @PutMapping("/v1/onboarding/business")
    Application business(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BusinessRequest request) {
        return onboarding.saveBusiness(merchant(jwt), request.businessType(), request.industry(), request.registrationNumber());
    }

    @PutMapping("/v1/onboarding/profile")
    Application profile(@AuthenticationPrincipal Jwt jwt, @RequestBody ProfileRequest request) {
        return onboarding.saveProfile(merchant(jwt), request.website(), request.description(), request.expectedMonthlyVolume());
    }

    @PutMapping("/v1/onboarding/bank")
    Application bank(@AuthenticationPrincipal Jwt jwt, @RequestBody BankRequest request) {
        return onboarding.saveBank(merchant(jwt), request.iban(), request.accountHolder());
    }

    @PostMapping("/v1/onboarding/persons")
    @ResponseStatus(HttpStatus.CREATED)
    Application addPerson(@AuthenticationPrincipal Jwt jwt, @RequestBody PersonRequest r) {
        return onboarding.savePerson(merchant(jwt), null, r.role(), r.name(), r.dateOfBirth(), r.nationality(), r.ownershipPct(),
                r.documentType(), r.documentNumber());
    }

    @PutMapping("/v1/onboarding/persons/{id}")
    Application updatePerson(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody PersonRequest r) {
        return onboarding.savePerson(merchant(jwt), id, r.role(), r.name(), r.dateOfBirth(), r.nationality(), r.ownershipPct(),
                r.documentType(), r.documentNumber());
    }

    @DeleteMapping("/v1/onboarding/persons/{id}")
    Application removePerson(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return onboarding.removePerson(merchant(jwt), id);
    }

    @PostMapping(value = "/v1/onboarding/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    DocumentInfo upload(@AuthenticationPrincipal Jwt jwt, @RequestParam String type,
                        @RequestParam(name = "person_id", required = false) String personId,
                        @RequestParam("file") MultipartFile file) throws IOException {
        return onboarding.addDocument(merchant(jwt), personId, type, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @GetMapping("/v1/onboarding/documents/{id}/file")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        DocumentFile file = onboarding.documentFile(merchant(jwt), id)
                .orElseThrow(() -> ApiException.notFound("document_not_found", "No such document"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.content());
    }

    @DeleteMapping("/v1/onboarding/documents/{id}")
    Application removeDocument(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return onboarding.removeDocument(merchant(jwt), id);
    }

    @PostMapping("/v1/onboarding/submit")
    Application submit(@AuthenticationPrincipal Jwt jwt, @RequestBody SubmitRequest request) {
        return onboarding.submit(jwt, request.acceptedTerms(), request.signedName());
    }
}
