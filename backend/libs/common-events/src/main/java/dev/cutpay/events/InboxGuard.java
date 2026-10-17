package dev.cutpay.events;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a Kafka consumer idempotent. Call {@link #firstTime(String)} at the start of the handler's
 * transaction: when it returns {@code false} the event was already processed and must be skipped.
 * Because the insert shares the transaction with the handler's own writes, both commit or neither does.
 */
public class InboxGuard {

    private final JdbcClient jdbc;

    public InboxGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String eventId) {
        return jdbc.sql("""
                        INSERT INTO processed_events (event_id) VALUES (:eventId)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .update() == 1;
    }
}
