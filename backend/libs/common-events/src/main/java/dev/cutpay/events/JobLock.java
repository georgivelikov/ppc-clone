package dev.cutpay.events;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Makes sure a scheduled job runs on only one instance at a time. It is a small stand-in for ShedLock
 * and uses the same table layout ({@code shedlock}, see outbox-schema.sql), so swapping the real library
 * in later only means deleting this class.
 *
 * <p>The lock lives in Postgres and expires on its own after {@code atMostFor}, so a crashed instance
 * cannot block the job forever. Lock times use the database clock, never the simulated one.
 */
public class JobLock {

    private static final Logger log = LoggerFactory.getLogger(JobLock.class);

    private final JdbcClient jdbc;
    private final String owner = UUID.randomUUID().toString();

    public JobLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs {@code task} if this instance wins the lock; returns whether it ran. */
    public boolean runLocked(String name, Duration atMostFor, Runnable task) {
        if (!acquire(name, atMostFor)) {
            log.debug("Job {} is running on another instance, skipping", name);
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            release(name);
        }
    }

    boolean acquire(String name, Duration atMostFor) {
        return jdbc.sql("""
                        INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
                        VALUES (:name, now() + make_interval(secs => :secs), now(), :owner)
                        ON CONFLICT (name) DO UPDATE
                           SET lock_until = EXCLUDED.lock_until,
                               locked_at  = EXCLUDED.locked_at,
                               locked_by  = EXCLUDED.locked_by
                         WHERE shedlock.lock_until <= now()
                        """)
                .param("name", name)
                .param("secs", (double) atMostFor.toSeconds())
                .param("owner", owner)
                .update() == 1;
    }

    void release(String name) {
        jdbc.sql("UPDATE shedlock SET lock_until = now() WHERE name = :name AND locked_by = :owner")
                .param("name", name)
                .param("owner", owner)
                .update();
    }
}
