package dev.cutpay.notifications.log;

import dev.cutpay.events.InboxGuard;
import dev.cutpay.notifications.config.NotificationProperties;
import dev.cutpay.notifications.mail.MailSender;
import dev.cutpay.notifications.mail.MailSender.Mail;
import dev.cutpay.notifications.mail.Templates;
import dev.cutpay.web.Ids;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a request for an email into rows of the log, then delivers them. Asking and sending are separate steps on
 * purpose: the request is saved first (so a crash or a mail outage loses nothing), and delivery retries with a
 * growing delay until it works or the attempts run out. Redelivered events change nothing (inbox guard, and a
 * unique key of event and recipient).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Emails a person has to opt in to. */
    public static final Set<String> OFF_BY_DEFAULT = Set.of("payment_new");

    /** Emails a person can switch off: the rest (password resets, invitations) always go. */
    public static final List<String> OPTIONAL = List.of("payment_new", "payout_paid", "dispute_opened", "dispute_closed",
            "kyc_approved", "kyc_rejected", "kyc_needs_info", "report_ready");

    private final JdbcClient jdbc;
    private final InboxGuard inbox;
    private final Templates templates;
    private final MailSender sender;
    private final NotificationProperties properties;
    private final TransactionTemplate tx;

    public NotificationService(JdbcClient jdbc, InboxGuard inbox, Templates templates, MailSender sender,
                               NotificationProperties properties, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.templates = templates;
        this.sender = sender;
        this.properties = properties;
        this.tx = tx;
    }

    /**
     * @param to       one address, or {@code null} to write to people at {@code merchantId}
     * @param audience with {@code merchantId}: {@code owners} (owner and admins) or {@code all}
     */
    public record Request(String eventId, String merchantId, String to, String audience, String template,
                          String locale, Map<String, Object> data) {
    }

    /** Saves the emails the request leads to and tries to send them once. Returns how many were queued. */
    public int handle(Request request) {
        List<String> queued = tx.execute(status -> save(request));
        for (String id : queued) {
            deliver(id);
        }
        return queued.size();
    }

    @Transactional
    List<String> save(Request request) {
        if (!inbox.firstTime(request.eventId() + "/" + request.template() + "/" + request.to())) {
            return List.of();
        }
        if (!templates.exists(request.template())) {
            log.warn("No email template named {}", request.template());
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Contact contact : recipients(request)) {
            String locale = contact.locale() != null ? contact.locale() : request.locale() != null ? request.locale() : "en";
            Map<String, Object> values = new java.util.HashMap<>(request.data());
            values.putIfAbsent("dashboard_url", properties.dashboardUrl());
            var rendered = templates.render(request.template(), locale, values);
            boolean wanted = contact.explicit() || isWanted(request.merchantId(), contact.email(), request.template());
            String id = Ids.generate("ntf");
            int inserted = jdbc.sql("""
                            INSERT INTO notification_log (id, event_id, merchant_id, recipient, template, locale, subject, body,
                                                          status, next_attempt)
                            VALUES (:id, :event, :merchant, :recipient, :template, :locale, :subject, :body, :status, now())
                            ON CONFLICT (event_id, recipient) DO NOTHING
                            """)
                    .param("id", id).param("event", request.eventId()).param("merchant", request.merchantId())
                    .param("recipient", contact.email()).param("template", request.template()).param("locale", locale)
                    .param("subject", rendered.subject()).param("body", rendered.html())
                    .param("status", wanted ? "queued" : "skipped")
                    .update();
            if (inserted == 1 && wanted) {
                ids.add(id);
            }
        }
        return ids;
    }

    private record Contact(String email, String locale, boolean explicit) {
    }

    private List<Contact> recipients(Request request) {
        if (request.to() != null) {
            return List.of(new Contact(request.to().trim(), null, true));
        }
        if (request.merchantId() == null) {
            return List.of();
        }
        boolean everyone = "all".equals(request.audience());
        return jdbc.sql("""
                        SELECT email, locale FROM merchant_contacts
                        WHERE merchant_id = :merchant AND (:everyone OR role IN ('owner', 'admin'))
                        ORDER BY email
                        """)
                .param("merchant", request.merchantId()).param("everyone", everyone)
                .query((rs, n) -> new Contact(rs.getString("email"), rs.getString("locale"), false))
                .list();
    }

    private boolean isWanted(String merchantId, String email, String template) {
        return jdbc.sql("SELECT enabled FROM notification_preferences WHERE merchant_id = :m AND email = :e AND template = :t")
                .param("m", merchantId).param("e", email).param("t", template)
                .query(Boolean.class).optional()
                .orElse(!OFF_BY_DEFAULT.contains(template));
    }

    /** Sends one saved email. Claims it first, so two instances never send the same one. */
    public boolean deliver(String id) {
        Instant now = Instant.now();
        // The claim pushes the next attempt a minute away: if this process dies mid-send, the retry job picks it up.
        int claimed = jdbc.sql("""
                        UPDATE notification_log SET next_attempt = :later
                        WHERE id = :id AND status IN ('queued', 'failed') AND next_attempt <= :now
                        """)
                .param("later", Timestamp.from(now.plus(Duration.ofMinutes(1))))
                .param("now", Timestamp.from(now)).param("id", id).update();
        if (claimed == 0) {
            return false;
        }
        var row = jdbc.sql("SELECT recipient, subject, body, attempts FROM notification_log WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)})
                .single();
        int attempts = (int) row[3] + 1;
        try {
            sender.send(new Mail((String) row[0], (String) row[1], (String) row[2]));
            jdbc.sql("UPDATE notification_log SET status = 'sent', attempts = :a, sent_at = now(), last_error = NULL, next_attempt = NULL WHERE id = :id")
                    .param("a", attempts).param("id", id).update();
            return true;
        } catch (Exception e) {
            boolean dead = attempts >= properties.maxAttempts();
            Instant next = now.plus(properties.retryBaseDelay().multipliedBy(1L << (attempts - 1)));
            jdbc.sql("""
                            UPDATE notification_log SET status = :status, attempts = :a, last_error = :err, next_attempt = :next
                            WHERE id = :id
                            """)
                    .param("status", dead ? "dead" : "failed").param("a", attempts)
                    .param("err", String.valueOf(e.getMessage())).param("next", dead ? null : Timestamp.from(next))
                    .param("id", id).update();
            log.warn("Email {} failed (attempt {}): {}", id, attempts, e.getMessage());
            return false;
        }
    }

    /** Retries what is due. Called by the job; returns how many were sent. */
    public int deliverDue() {
        List<String> due = jdbc.sql("""
                        SELECT id FROM notification_log
                        WHERE status IN ('queued', 'failed') AND next_attempt <= now()
                        ORDER BY created_at LIMIT 50
                        """).query(String.class).list();
        int sent = 0;
        for (String id : due) {
            if (deliver(id)) {
                sent++;
            }
        }
        return sent;
    }

    // --- contacts, preferences, log ------------------------------------------------------------------------------

    @Transactional
    public void upsertContact(String merchantId, String userId, String email, String fullName, String role, String locale) {
        jdbc.sql("""
                        INSERT INTO merchant_contacts (merchant_id, user_id, email, full_name, role, locale)
                        VALUES (:m, :u, :e, :n, :r, :l)
                        ON CONFLICT (merchant_id, user_id) DO UPDATE
                          SET email = EXCLUDED.email, full_name = EXCLUDED.full_name, role = EXCLUDED.role
                        """)
                .param("m", merchantId).param("u", userId).param("e", email).param("n", fullName)
                .param("r", role.toLowerCase(Locale.ROOT)).param("l", locale == null ? "en" : locale).update();
    }

    @Transactional
    public void removeContact(String merchantId, String userId) {
        jdbc.sql("DELETE FROM merchant_contacts WHERE merchant_id = :m AND user_id = :u")
                .param("m", merchantId).param("u", userId).update();
    }

    @Transactional
    public void rememberMerchant(String merchantId, String name) {
        jdbc.sql("""
                        INSERT INTO merchants (merchant_id, name) VALUES (:m, :n)
                        ON CONFLICT (merchant_id) DO UPDATE SET name = EXCLUDED.name
                        """).param("m", merchantId).param("n", name).update();
    }

    public String merchantName(String merchantId) {
        return jdbc.sql("SELECT name FROM merchants WHERE merchant_id = :m").param("m", merchantId)
                .query(String.class).optional().orElse("the business");
    }

    public String dashboardUrl() {
        return properties.dashboardUrl();
    }

    public record Preference(String template, boolean enabled) {
    }

    @Transactional(readOnly = true)
    public List<Preference> preferences(String merchantId, String email) {
        return OPTIONAL.stream().map(t -> new Preference(t, isWanted(merchantId, email, t))).toList();
    }

    @Transactional
    public void setPreference(String merchantId, String email, String template, boolean enabled) {
        jdbc.sql("""
                        INSERT INTO notification_preferences (merchant_id, email, template, enabled)
                        VALUES (:m, :e, :t, :on)
                        ON CONFLICT (merchant_id, email, template) DO UPDATE SET enabled = EXCLUDED.enabled
                        """)
                .param("m", merchantId).param("e", email).param("t", template).param("on", enabled).update();
    }

    public record Entry(String id, String recipient, String template, String subject, String status, int attempts,
                        String lastError, Instant createdAt, Instant sentAt) {
    }

    @Transactional(readOnly = true)
    public List<Entry> log(String merchantId, int limit) {
        return jdbc.sql("""
                        SELECT id, recipient, template, subject, status, attempts, last_error, created_at, sent_at
                        FROM notification_log WHERE merchant_id = :m ORDER BY created_at DESC, id LIMIT :limit
                        """)
                .param("m", merchantId).param("limit", limit)
                .query((rs, n) -> new Entry(rs.getString("id"), rs.getString("recipient"), rs.getString("template"),
                        rs.getString("subject"), rs.getString("status"), rs.getInt("attempts"),
                        rs.getString("last_error"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant()))
                .list();
    }
}
