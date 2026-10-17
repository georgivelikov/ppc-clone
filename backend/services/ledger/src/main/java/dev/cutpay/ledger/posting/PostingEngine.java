package dev.cutpay.ledger.posting;

import dev.cutpay.web.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes balanced journal entries. The caller's transaction decides when everything commits, so a
 * consumer can record "event processed" and its postings atomically (the exactly-once pattern).
 *
 * <p>The invariant "postings of an entry sum to zero" is enforced twice: here, before anything is
 * written, and by a deferred constraint trigger in the database.
 */
@Component
public class PostingEngine {

    /** Positive amount = debit, negative = credit. */
    public record Line(String accountId, long amount) {
    }

    public record Entry(String eventId, String entryType, String description, String merchantId, boolean livemode,
                        String currency, String sourceId, Instant occurredAt, List<Line> lines) {
    }

    private final JdbcClient jdbc;

    public PostingEngine(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Posts the entry unless an entry for the same event already exists.
     *
     * @return the new journal entry id, or empty if this event was posted before
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> post(Entry entry) {
        List<Line> lines = consolidate(entry.lines());
        long total = lines.stream().mapToLong(Line::amount).sum();
        if (total != 0) {
            throw new IllegalArgumentException(
                    "Journal entry for " + entry.eventId() + " does not balance (sum = " + total + ")");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Journal entry for " + entry.eventId() + " has no postings");
        }

        String entryId = Ids.generate("je");
        int inserted = jdbc.sql("""
                        INSERT INTO journal_entries (id, event_id, entry_type, description, merchant_id, livemode,
                                                     currency, source_id, occurred_at)
                        VALUES (:id, :eventId, :type, :description, :merchant, :livemode, :currency, :source,
                                :occurredAt)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", entryId)
                .param("eventId", entry.eventId())
                .param("type", entry.entryType())
                .param("description", entry.description())
                .param("merchant", entry.merchantId())
                .param("livemode", entry.livemode())
                .param("currency", entry.currency())
                .param("source", entry.sourceId())
                .param("occurredAt", Sql.ts(entry.occurredAt()))
                .update();
        if (inserted == 0) {
            return Optional.empty();
        }

        for (Line line : lines) {
            jdbc.sql("""
                            INSERT INTO postings (journal_entry_id, account_id, amount, currency)
                            VALUES (:entry, :account, :amount, :currency)
                            """)
                    .param("entry", entryId)
                    .param("account", line.accountId())
                    .param("amount", line.amount())
                    .param("currency", entry.currency())
                    .update();
        }
        // Lines are sorted by account, so concurrent transactions lock balance rows in the same order.
        for (Line line : lines) {
            jdbc.sql("""
                            INSERT INTO balances (account_id, amount, version) VALUES (:account, :amount, 1)
                            ON CONFLICT (account_id) DO UPDATE
                               SET amount = balances.amount + EXCLUDED.amount, version = balances.version + 1
                            """)
                    .param("account", line.accountId())
                    .param("amount", line.amount())
                    .update();
        }
        return Optional.of(entryId);
    }

    /** Merges lines on the same account, drops zero lines and sorts by account id. */
    static List<Line> consolidate(List<Line> lines) {
        Map<String, Long> byAccount = new LinkedHashMap<>();
        for (Line line : lines) {
            byAccount.merge(line.accountId(), line.amount(), Math::addExact);
        }
        List<Line> merged = new ArrayList<>();
        byAccount.forEach((account, amount) -> {
            if (amount != 0) {
                merged.add(new Line(account, amount));
            }
        });
        merged.sort(Comparator.comparing(Line::accountId));
        return merged;
    }
}
