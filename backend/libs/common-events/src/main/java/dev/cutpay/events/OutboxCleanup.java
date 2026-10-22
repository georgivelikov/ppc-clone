package dev.cutpay.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * With change data capture (Debezium) nothing marks an outbox row as published: the row's insert is read from the
 * database log and its job is done. The table would only grow, so rows older than the retention are deleted.
 * Deleting does no harm to the stream: the router ignores deletes. Not used when the polling relay is on, which
 * marks rows {@code published_at} and is cleaned up elsewhere.
 */
public class OutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final JdbcClient jdbc;
    private final String retention;

    public OutboxCleanup(JdbcClient jdbc, String retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${cutpay.outbox.cleanup-interval:PT1M}")
    public void cleanup() {
        int deleted = jdbc.sql("DELETE FROM outbox WHERE created_at < now() - CAST(:retention AS interval)")
                .param("retention", retention)
                .update();
        if (deleted > 0) {
            log.debug("Deleted {} outbox rows older than {}", deleted, retention);
        }
    }
}
