package dev.cutpay.ledger.posting;

import dev.cutpay.web.Ids;
import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Finds or lazily creates ledger accounts. Safe under concurrency: the unique constraint decides the winner. */
@Repository
public class Accounts {

    private final JdbcClient jdbc;

    public Accounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String merchant(String merchantId, boolean livemode, AccountType type, String currency) {
        return getOrCreate(merchantId, livemode, type, currency);
    }

    /** Platform-wide accounts have no merchant. */
    public String platform(boolean livemode, AccountType type, String currency) {
        return getOrCreate(null, livemode, type, currency);
    }

    private String getOrCreate(String merchantId, boolean livemode, AccountType type, String currency) {
        jdbc.sql("""
                        INSERT INTO accounts (id, merchant_id, livemode, type, currency)
                        VALUES (:id, :merchant, :livemode, :type, :currency)
                        ON CONFLICT ON CONSTRAINT accounts_unique DO NOTHING
                        """)
                .param("id", Ids.generate("acc"))
                .param("merchant", merchantId, Types.VARCHAR)
                .param("livemode", livemode)
                .param("type", type.wire())
                .param("currency", currency)
                .update();
        return jdbc.sql("""
                        SELECT id FROM accounts
                         WHERE merchant_id IS NOT DISTINCT FROM CAST(:merchant AS TEXT)
                           AND livemode = :livemode AND type = :type AND currency = :currency
                        """)
                .param("merchant", merchantId, Types.VARCHAR)
                .param("livemode", livemode)
                .param("type", type.wire())
                .param("currency", currency)
                .query(String.class)
                .single();
    }
}
