package dev.cutpay.billing.settings;

import static dev.cutpay.billing.config.Sql.ts;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** How a merchant wants unpaid invoices handled: when they are retried and what happens after the last retry. */
@Component
public class BillingSettings {

    public static final List<Integer> DEFAULT_RETRY_DAYS = List.of(1, 3, 5, 7);

    public record Settings(List<Integer> retryDays, String finalAction) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    BillingSettings(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Settings get(String merchantId) {
        return jdbc.sql("SELECT array_to_string(retry_days, ',') AS days, final_action FROM billing_settings WHERE merchant_id = :m")
                .param("m", merchantId)
                .query((rs, n) -> new Settings(parse(rs.getString("days")), rs.getString("final_action")))
                .optional().orElse(new Settings(DEFAULT_RETRY_DAYS, "cancel"));
    }

    public Settings save(String merchantId, List<Integer> retryDays, String finalAction) {
        jdbc.sql("""
                        INSERT INTO billing_settings (merchant_id, retry_days, final_action, updated_at)
                        VALUES (:m, string_to_array(:days, ',')::int[], :action, :now)
                        ON CONFLICT (merchant_id) DO UPDATE SET retry_days = EXCLUDED.retry_days,
                               final_action = EXCLUDED.final_action, updated_at = EXCLUDED.updated_at
                        """)
                .param("m", merchantId)
                .param("days", String.join(",", retryDays.stream().map(String::valueOf).toList()))
                .param("action", finalAction).param("now", ts(clock.instant())).update();
        return get(merchantId);
    }

    private static List<Integer> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(Integer::parseInt).toList();
    }
}
