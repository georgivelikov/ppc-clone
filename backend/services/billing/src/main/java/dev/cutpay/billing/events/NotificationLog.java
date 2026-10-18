package dev.cutpay.billing.events;

import static dev.cutpay.billing.config.Sql.ts;

import dev.cutpay.web.Ids;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The emails the platform would send to customers: payment failed, retry scheduled, subscription canceled. They are
 * recorded and logged until real delivery arrives in phase 8, so what a customer would have read can be checked.
 */
@Component
public class NotificationLog {

    private static final Logger log = LoggerFactory.getLogger(NotificationLog.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    NotificationLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void send(String merchantId, boolean livemode, String kind, String recipient, String subject, String body,
                     String relatedId) {
        jdbc.sql("""
                        INSERT INTO notifications (id, merchant_id, livemode, kind, recipient, subject, body, related_id,
                                                   created_at)
                        VALUES (:id, :merchant, :livemode, :kind, :recipient, :subject, :body, :related, :now)
                        """)
                .param("id", Ids.generate("ntf")).param("merchant", merchantId).param("livemode", livemode)
                .param("kind", kind).param("recipient", recipient).param("subject", subject).param("body", body)
                .param("related", relatedId).param("now", ts(clock.instant())).update();
        log.info("email to {}: {} [{}]", recipient, subject, kind);
    }
}
