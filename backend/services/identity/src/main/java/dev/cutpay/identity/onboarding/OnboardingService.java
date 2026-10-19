package dev.cutpay.identity.onboarding;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.identity.audit.AuditLog;
import dev.cutpay.identity.domain.Merchant;
import dev.cutpay.identity.domain.Repositories.MerchantRepository;
import dev.cutpay.identity.onboarding.KycClient.Company;
import dev.cutpay.identity.onboarding.RiskScorer.Input;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A business's application to take live payments, from the first answer to the decision.
 *
 * <p>Status lives on the merchant: {@code pending_kyc} → {@code in_review} → {@code active} | {@code rejected} |
 * {@code needs_more_info}. While it is not {@code active} the business works in test mode only. An application can be edited
 * when it is {@code pending_kyc} or {@code needs_more_info}; submitting it screens the people, starts the document checks and
 * scores the risk. A low score is decided by the checks alone; a high one waits for a person (the back-office).
 */
@Service
public class OnboardingService {

    public static final String TOPIC = "onboarding.events";
    static final Set<String> BUSINESS_TYPES = Set.of("sole_trader", "company", "nonprofit");
    static final Set<String> ROLES = Set.of("representative", "ubo", "director");
    static final Set<String> DOCUMENT_TYPES = Set.of("id_card", "passport", "driving_licence");
    static final Set<String> FILE_TYPES = Set.of("id_front", "id_back", "selfie", "bank_proof");
    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final MerchantRepository merchants;
    private final KycClient kyc;
    private final OutboxWriter outbox;
    private final AuditLog audit;
    private final JsonMapper mapper;

    public OnboardingService(JdbcClient jdbc, MerchantRepository merchants, KycClient kyc, OutboxWriter outbox, AuditLog audit,
                             JsonMapper mapper) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.kyc = kyc;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
    }

    // --- what the dashboard reads -----------------------------------------------------------------------------

    public record Person(String id, String role, String name, LocalDate dateOfBirth, String nationality, Integer ownershipPct,
                         String documentType, String documentNumber, String verificationStatus, String verificationReason,
                         String screening) {
    }

    public record DocumentInfo(String id, String personId, String type, String filename, String contentType, int size,
                               Instant uploadedAt) {
    }

    public record DocumentFile(String filename, String contentType, byte[] content) {
    }

    public record Application(String id, String merchantId, String status, Map<String, Object> data, List<Person> persons,
                              List<DocumentInfo> documents, List<String> missing, Integer riskScore, List<String> riskReasons,
                              boolean manualReview, Instant submittedAt, Instant decidedAt, String decidedBy,
                              String decisionReason, String signedName) {
    }

    @Transactional
    public Application view(String merchantId) {
        ensure(merchantId);
        return load(merchantId);
    }

    // --- the steps --------------------------------------------------------------------------------------------

    @Transactional
    public Application saveBusiness(String merchantId, String businessType, String industry, String registrationNumber) {
        requireEditable(merchantId);
        if (!BUSINESS_TYPES.contains(businessType)) {
            throw ApiException.invalidRequest("invalid_business_type", "Choose sole trader, company or nonprofit", "business_type");
        }
        if (!RiskScorer.INDUSTRIES.contains(industry)) {
            throw ApiException.invalidRequest("invalid_industry", "Choose one of the listed industries", "industry");
        }
        Merchant merchant = merchants.findById(merchantId).orElseThrow();
        Map<String, Object> data = data(merchantId);
        data.put("business_type", businessType);
        data.put("industry", industry);
        data.put("country", merchant.getCountry());
        if (registrationNumber != null && !registrationNumber.isBlank()) {
            String number = registrationNumber.trim();
            Company company = kyc.company(merchant.getCountry(), number).orElseThrow(() -> ApiException.notFound("company_not_found",
                    "No company with that number is in the register of " + merchant.getCountry()));
            data.put("registration_number", company.registrationNumber());
            data.put("company", mapper.convertValue(company, MAP));
        } else if (!"sole_trader".equals(businessType)) {
            throw ApiException.invalidRequest("registration_number_required", "Enter the registration number of the business",
                    "registration_number");
        } else {
            data.remove("registration_number");
            data.remove("company");
        }
        save(merchantId, data);
        return load(merchantId);
    }

    @Transactional
    public Application saveProfile(String merchantId, String website, String description, long expectedMonthlyVolume) {
        requireEditable(merchantId);
        if (description == null || description.trim().length() < 20) {
            throw ApiException.invalidRequest("description_too_short", "Describe what you sell in at least 20 characters", "description");
        }
        if (expectedMonthlyVolume <= 0) {
            throw ApiException.invalidRequest("invalid_volume", "Enter what you expect to take each month", "expected_monthly_volume");
        }
        if (website != null && !website.isBlank() && !website.trim().matches("^https?://[^\\s]+$")) {
            throw ApiException.invalidRequest("invalid_website", "A website starts with http:// or https://", "website");
        }
        Map<String, Object> data = data(merchantId);
        data.put("website", website == null ? "" : website.trim());
        data.put("description", description.trim());
        data.put("expected_monthly_volume", expectedMonthlyVolume);
        save(merchantId, data);
        return load(merchantId);
    }

    @Transactional
    public Application saveBank(String merchantId, String iban, String accountHolder) {
        requireEditable(merchantId);
        String clean = iban == null ? "" : iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!Iban.isValid(clean)) {
            throw ApiException.invalidRequest("invalid_iban", "That IBAN is not valid", "iban");
        }
        if (accountHolder == null || accountHolder.trim().length() < 2) {
            throw ApiException.invalidRequest("account_holder_required", "Enter the name on the account", "account_holder");
        }
        Map<String, Object> data = data(merchantId);
        data.put("iban", clean);
        data.put("account_holder", accountHolder.trim());
        save(merchantId, data);
        return load(merchantId);
    }

    @Transactional
    public Application savePerson(String merchantId, String personId, String role, String name, String dateOfBirth,
                                  String nationality, Integer ownershipPct, String documentType, String documentNumber) {
        requireEditable(merchantId);
        if (!ROLES.contains(role)) {
            throw ApiException.invalidRequest("invalid_role", "Choose representative, ubo or director", "role");
        }
        if (name == null || name.trim().length() < 2) {
            throw ApiException.invalidRequest("name_required", "Enter the full name", "name");
        }
        LocalDate born;
        try {
            born = LocalDate.parse(dateOfBirth);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.invalidRequest("invalid_date_of_birth", "Enter the date of birth as year-month-day", "date_of_birth");
        }
        if (born.isAfter(LocalDate.now().minusYears(18))) {
            throw ApiException.invalidRequest("too_young", "A person in charge must be at least 18", "date_of_birth");
        }
        if (nationality == null || !nationality.matches("^[A-Za-z]{2}$")) {
            throw ApiException.invalidRequest("invalid_nationality", "Enter a two-letter country code", "nationality");
        }
        if (!DOCUMENT_TYPES.contains(documentType)) {
            throw ApiException.invalidRequest("invalid_document_type", "Choose id card, passport or driving licence", "document_type");
        }
        if (documentNumber == null || documentNumber.trim().length() < 5) {
            throw ApiException.invalidRequest("invalid_document_number", "Enter the number on the document", "document_number");
        }
        if ("ubo".equals(role) && (ownershipPct == null || ownershipPct < 25 || ownershipPct > 100)) {
            throw ApiException.invalidRequest("invalid_ownership", "An owner holds at least 25% of the business", "ownership_pct");
        }
        if (personId == null) {
            jdbc.sql("""
                            INSERT INTO persons (id, merchant_id, role, name, date_of_birth, nationality, ownership_pct,
                                                 document_type, document_number)
                            VALUES (:id, :m, :role, :name, :dob, :nat, :pct, :dtype, :dnum)
                            """)
                    .param("id", Ids.generate("per")).param("m", merchantId).param("role", role).param("name", name.trim())
                    .param("dob", java.sql.Date.valueOf(born)).param("nat", nationality.toUpperCase(Locale.ROOT))
                    .param("pct", ownershipPct, java.sql.Types.INTEGER).param("dtype", documentType)
                    .param("dnum", documentNumber.trim()).update();
        } else {
            int changed = jdbc.sql("""
                            UPDATE persons SET role = :role, name = :name, date_of_birth = :dob, nationality = :nat,
                                   ownership_pct = :pct, document_type = :dtype, document_number = :dnum,
                                   verification_status = CASE WHEN document_number = :dnum AND verification_status = 'approved'
                                                              THEN verification_status ELSE 'not_started' END,
                                   verification_reason = NULL
                            WHERE id = :id AND merchant_id = :m
                            """)
                    .param("id", personId).param("m", merchantId).param("role", role).param("name", name.trim())
                    .param("dob", java.sql.Date.valueOf(born)).param("nat", nationality.toUpperCase(Locale.ROOT))
                    .param("pct", ownershipPct, java.sql.Types.INTEGER).param("dtype", documentType)
                    .param("dnum", documentNumber.trim()).update();
            if (changed == 0) {
                throw ApiException.notFound("person_not_found", "No such person");
            }
        }
        touch(merchantId);
        return load(merchantId);
    }

    @Transactional
    public Application removePerson(String merchantId, String personId) {
        requireEditable(merchantId);
        jdbc.sql("DELETE FROM documents WHERE person_id = :p AND merchant_id = :m").param("p", personId).param("m", merchantId).update();
        if (jdbc.sql("DELETE FROM persons WHERE id = :p AND merchant_id = :m").param("p", personId).param("m", merchantId).update() == 0) {
            throw ApiException.notFound("person_not_found", "No such person");
        }
        touch(merchantId);
        return load(merchantId);
    }

    @Transactional
    public DocumentInfo addDocument(String merchantId, String personId, String type, String filename, String contentType,
                                    byte[] content) {
        requireEditable(merchantId);
        if (!FILE_TYPES.contains(type)) {
            throw ApiException.invalidRequest("invalid_document_type", "Choose id_front, id_back, selfie or bank_proof", "type");
        }
        boolean forPerson = !"bank_proof".equals(type);
        if (forPerson == (personId == null)) {
            throw ApiException.invalidRequest("invalid_person", forPerson ? "Say whose document this is" : "Proof of the bank account belongs to the business",
                    "person_id");
        }
        if (personId != null && jdbc.sql("SELECT count(*) FROM persons WHERE id = :p AND merchant_id = :m")
                .param("p", personId).param("m", merchantId).query(Integer.class).single() == 0) {
            throw ApiException.notFound("person_not_found", "No such person");
        }
        if (content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw ApiException.invalidRequest("invalid_file", "A file must be between 1 byte and 5 MB", "file");
        }
        String type0 = contentType == null ? "" : contentType;
        if (!type0.startsWith("image/") && !type0.equals("application/pdf")) {
            throw ApiException.invalidRequest("invalid_file_type", "Upload an image or a PDF", "file");
        }
        // One file of each kind per person: a new upload replaces the old one.
        jdbc.sql("DELETE FROM documents WHERE merchant_id = :m AND type = :t AND person_id IS NOT DISTINCT FROM CAST(:p AS TEXT)")
                .param("m", merchantId).param("t", type).param("p", personId, java.sql.Types.VARCHAR).update();
        String id = Ids.generate("doc");
        String safe = filename == null || filename.isBlank() ? "document" : filename.replaceAll("[^A-Za-z0-9._ -]", "_");
        jdbc.sql("""
                        INSERT INTO documents (id, merchant_id, person_id, type, filename, content_type, size, content)
                        VALUES (:id, :m, :p, :t, :f, :c, :s, :content)
                        """)
                .param("id", id).param("m", merchantId).param("p", personId, java.sql.Types.VARCHAR).param("t", type)
                .param("f", safe).param("c", type0).param("s", content.length).param("content", content).update();
        touch(merchantId);
        return documents(merchantId).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<DocumentFile> documentFile(String merchantId, String documentId) {
        return jdbc.sql("SELECT filename, content_type, content FROM documents WHERE id = :d AND merchant_id = :m")
                .param("d", documentId).param("m", merchantId)
                .query((rs, n) -> new DocumentFile(rs.getString(1), rs.getString(2), rs.getBytes(3))).optional();
    }

    @Transactional
    public Application removeDocument(String merchantId, String documentId) {
        requireEditable(merchantId);
        jdbc.sql("DELETE FROM documents WHERE id = :d AND merchant_id = :m").param("d", documentId).param("m", merchantId).update();
        touch(merchantId);
        return load(merchantId);
    }

    // --- submitting and deciding ------------------------------------------------------------------------------

    /** What is still missing, in the words the wizard shows. Empty when the application can be submitted. */
    List<String> missing(Map<String, Object> data, List<Person> persons, List<DocumentInfo> documents) {
        List<String> missing = new ArrayList<>();
        if (data.get("business_type") == null || data.get("industry") == null) {
            missing.add("business");
        } else if (!"sole_trader".equals(data.get("business_type")) && data.get("company") == null) {
            missing.add("business");
        }
        boolean company = !"sole_trader".equals(data.get("business_type"));
        boolean representative = persons.stream().anyMatch(p -> p.role().equals("representative"));
        boolean owner = !company || persons.stream().anyMatch(p -> p.role().equals("ubo") && p.ownershipPct() != null && p.ownershipPct() >= 25);
        if (!representative || !owner) {
            missing.add("people");
        } else {
            for (Person p : persons) {
                boolean front = documents.stream().anyMatch(d -> p.id().equals(d.personId()) && d.type().equals("id_front"));
                boolean selfie = documents.stream().anyMatch(d -> p.id().equals(d.personId()) && d.type().equals("selfie"));
                if (!front || !selfie) {
                    missing.add("documents");
                    break;
                }
            }
        }
        if (data.get("iban") == null || documents.stream().noneMatch(d -> d.type().equals("bank_proof"))) {
            missing.add("bank");
        }
        if (data.get("description") == null || data.get("expected_monthly_volume") == null) {
            missing.add("profile");
        }
        return missing;
    }

    @Transactional
    public Application submit(Jwt actor, boolean acceptedTerms, String signedName) {
        String merchantId = actor.getClaimAsString("mid");
        requireEditable(merchantId);
        Application app = load(merchantId);
        if (!app.missing().isEmpty()) {
            throw ApiException.invalidRequest("application_incomplete", "Finish these steps first: " + String.join(", ", app.missing()),
                    "application");
        }
        if (!acceptedTerms) {
            throw ApiException.invalidRequest("terms_required", "Accept the terms to submit", "accepted_terms");
        }
        if (signedName == null || signedName.trim().length() < 2) {
            throw ApiException.invalidRequest("signature_required", "Type your full name to sign", "signed_name");
        }
        Merchant merchant = merchants.findById(merchantId).orElseThrow();

        // Screen everyone, and ask for a fresh check of every document that is not already approved.
        String worst = "clear";
        for (Person p : app.persons()) {
            var screening = kyc.screen(p.name(), p.nationality());
            String result = screening.hits().stream().anyMatch(h -> h.list().equals("sanctions")) ? "sanctions"
                    : screening.hits().isEmpty() ? "clear" : "pep";
            jdbc.sql("UPDATE persons SET screening = :s WHERE id = :id").param("s", result).param("id", p.id()).update();
            if (result.equals("sanctions") || (result.equals("pep") && worst.equals("clear"))) {
                worst = result;
            }
            if (!"approved".equals(p.verificationStatus())) {
                var started = kyc.startVerification(p.documentNumber(), p.documentType(), p.name(), p.dateOfBirth(), p.nationality());
                jdbc.sql("UPDATE persons SET verification_id = :v, verification_status = 'pending', verification_reason = NULL WHERE id = :id")
                        .param("v", started.id()).param("id", p.id()).update();
            }
        }
        boolean dissolved = app.data().get("company") instanceof Map<?, ?> c && "dissolved".equals(c.get("status"));
        var risk = RiskScorer.score(new Input((String) app.data().get("business_type"), (String) app.data().get("industry"),
                merchant.getCountry(), ((Number) app.data().get("expected_monthly_volume")).longValue(), worst, dissolved));

        jdbc.sql("""
                        UPDATE onboarding_applications
                           SET risk_score = :score, risk_reasons = :reasons, manual_review = :manual, signed_name = :name,
                               terms_accepted_at = :now, submitted_at = :now, decided_at = NULL, decided_by = NULL,
                               decision_reason = NULL, updated_at = :now
                         WHERE merchant_id = :m
                        """)
                .param("score", risk.score()).param("reasons", String.join("\n", risk.reasons()))
                .param("manual", risk.needsManualReview()).param("name", signedName.trim())
                .param("now", Timestamp.from(Instant.now())).param("m", merchantId).update();
        merchant.setStatus(Merchant.Status.IN_REVIEW);
        audit.record(merchantId, actor, "onboarding.submitted", "merchant", merchantId, "risk " + risk.score());
        outbox.write(TOPIC, "ApplicationSubmitted", merchantId, merchantId, null,
                Map.of("merchant_id", merchantId, "risk_score", risk.score(), "manual_review", risk.needsManualReview()));
        return load(merchantId);
    }

    /**
     * The checks have answered for applications that need no person: decide them. Returns how many were decided.
     * Called by the job; a provider that does not answer leaves the application waiting.
     */
    @Transactional
    public int pollVerifications() {
        int decided = 0;
        List<String> pending = jdbc.sql("""
                        SELECT a.merchant_id FROM onboarding_applications a JOIN merchants m ON m.id = a.merchant_id
                         WHERE m.status = 'IN_REVIEW' ORDER BY a.submitted_at LIMIT 50
                        """).query(String.class).list();
        for (String merchantId : pending) {
            for (Person p : persons(merchantId)) {
                if (!"pending".equals(p.verificationStatus())) {
                    continue;
                }
                String verificationId = jdbc.sql("SELECT verification_id FROM persons WHERE id = :id").param("id", p.id())
                        .query(String.class).single();
                kyc.verification(verificationId).filter(v -> !"pending".equals(v.status())).ifPresent(v ->
                        jdbc.sql("UPDATE persons SET verification_status = :s, verification_reason = :r WHERE id = :id")
                                .param("s", v.status()).param("r", v.reason(), java.sql.Types.VARCHAR).param("id", p.id()).update());
            }
            Application app = load(merchantId);
            if (app.manualReview()) {
                continue; // a person decides, whatever the checks say
            }
            List<Person> people = app.persons();
            var rejected = people.stream().filter(p -> p.verificationStatus().equals("rejected")).findFirst();
            var unclear = people.stream().filter(p -> p.verificationStatus().equals("needs_more_info")).findFirst();
            if (rejected.isPresent()) {
                decide(merchantId, Merchant.Status.REJECTED, rejected.get().name() + ": " + rejected.get().verificationReason(), "system");
                decided++;
            } else if (unclear.isPresent()) {
                decide(merchantId, Merchant.Status.NEEDS_MORE_INFO, unclear.get().name() + ": " + unclear.get().verificationReason(), "system");
                decided++;
            } else if (people.stream().allMatch(p -> p.verificationStatus().equals("approved"))) {
                decide(merchantId, Merchant.Status.ACTIVE, null, "system");
                decided++;
            }
        }
        return decided;
    }

    /** Records a decision, by the checks ({@code system}) or by a person at Cutpay. */
    @Transactional
    public void decide(String merchantId, Merchant.Status status, String reason, String decidedBy) {
        if (status != Merchant.Status.ACTIVE && status != Merchant.Status.REJECTED && status != Merchant.Status.NEEDS_MORE_INFO) {
            throw new IllegalArgumentException("Not a decision: " + status);
        }
        Merchant merchant = merchants.findById(merchantId).orElseThrow();
        if (merchant.getStatus() != Merchant.Status.IN_REVIEW) {
            throw ApiException.conflict("not_in_review", "This application is not waiting for a decision", null);
        }
        merchant.setStatus(status);
        jdbc.sql("UPDATE onboarding_applications SET decided_at = now(), decided_by = :by, decision_reason = :r, updated_at = now() WHERE merchant_id = :m")
                .param("by", decidedBy).param("r", reason, java.sql.Types.VARCHAR).param("m", merchantId).update();
        String wire = status.name().toLowerCase(Locale.ROOT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchant_id", merchantId);
        payload.put("status", wire);
        payload.put("reason", reason);
        payload.put("decided_by", decidedBy);
        outbox.write(TOPIC, "ApplicationDecided", merchantId, merchantId, null, payload);
        outbox.write("merchant.events", "MerchantStatusChanged", merchantId, merchantId, null,
                Map.of("merchant_id", merchantId, "status", wire));
        audit.record(merchantId, null, decidedBy, "onboarding." + wire, "merchant", merchantId, reason);
    }

    // --- internals ------------------------------------------------------------------------------------------------

    private void ensure(String merchantId) {
        jdbc.sql("INSERT INTO onboarding_applications (id, merchant_id) VALUES (:id, :m) ON CONFLICT (merchant_id) DO NOTHING")
                .param("id", Ids.generate("app")).param("m", merchantId).update();
    }

    private void requireEditable(String merchantId) {
        ensure(merchantId);
        Merchant.Status status = merchants.findById(merchantId).orElseThrow().getStatus();
        if (status != Merchant.Status.PENDING_KYC && status != Merchant.Status.NEEDS_MORE_INFO) {
            throw ApiException.conflict("application_locked", "The application cannot be changed while it is "
                    + status.name().toLowerCase(Locale.ROOT).replace('_', ' '), null);
        }
    }

    private Map<String, Object> data(String merchantId) {
        return new LinkedHashMap<>(mapper.readValue(jdbc.sql("SELECT data FROM onboarding_applications WHERE merchant_id = :m")
                .param("m", merchantId).query(String.class).single(), MAP));
    }

    private void save(String merchantId, Map<String, Object> data) {
        jdbc.sql("UPDATE onboarding_applications SET data = :d, updated_at = now() WHERE merchant_id = :m")
                .param("d", mapper.writeValueAsString(data)).param("m", merchantId).update();
    }

    private void touch(String merchantId) {
        jdbc.sql("UPDATE onboarding_applications SET updated_at = now() WHERE merchant_id = :m").param("m", merchantId).update();
    }

    List<Person> persons(String merchantId) {
        return jdbc.sql("""
                        SELECT id, role, name, date_of_birth, nationality, ownership_pct, document_type, document_number,
                               verification_status, verification_reason, screening
                        FROM persons WHERE merchant_id = :m ORDER BY created_at, id
                        """)
                .param("m", merchantId)
                .query((rs, n) -> new Person(rs.getString("id"), rs.getString("role"), rs.getString("name"),
                        rs.getDate("date_of_birth").toLocalDate(), rs.getString("nationality"),
                        (Integer) rs.getObject("ownership_pct"), rs.getString("document_type"), rs.getString("document_number"),
                        rs.getString("verification_status"), rs.getString("verification_reason"), rs.getString("screening")))
                .list();
    }

    List<DocumentInfo> documents(String merchantId) {
        return jdbc.sql("SELECT id, person_id, type, filename, content_type, size, uploaded_at FROM documents WHERE merchant_id = :m ORDER BY uploaded_at, id")
                .param("m", merchantId)
                .query((rs, n) -> new DocumentInfo(rs.getString("id"), rs.getString("person_id"), rs.getString("type"),
                        rs.getString("filename"), rs.getString("content_type"), rs.getInt("size"),
                        rs.getTimestamp("uploaded_at").toInstant()))
                .list();
    }

    Application load(String merchantId) {
        Merchant merchant = merchants.findById(merchantId).orElseThrow();
        Map<String, Object> data = data(merchantId);
        List<Person> persons = persons(merchantId);
        List<DocumentInfo> documents = documents(merchantId);
        return jdbc.sql("""
                        SELECT id, risk_score, risk_reasons, manual_review, submitted_at, decided_at, decided_by, decision_reason, signed_name
                        FROM onboarding_applications WHERE merchant_id = :m
                        """)
                .param("m", merchantId)
                .query((rs, n) -> new Application(rs.getString("id"), merchantId, merchant.getStatus().name().toLowerCase(Locale.ROOT),
                        data, persons, documents, missing(data, persons, documents), (Integer) rs.getObject("risk_score"),
                        rs.getString("risk_reasons") == null || rs.getString("risk_reasons").isBlank() ? List.of()
                                : List.of(rs.getString("risk_reasons").split("\n")),
                        rs.getBoolean("manual_review"),
                        rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
                        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                        rs.getString("decided_by"), rs.getString("decision_reason"), rs.getString("signed_name")))
                .single();
    }
}
