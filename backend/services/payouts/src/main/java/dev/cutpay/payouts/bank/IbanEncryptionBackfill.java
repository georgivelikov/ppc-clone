package dev.cutpay.payouts.bank;

import dev.cutpay.crypto.FieldCipher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Accounts stored before encryption was switched on hold their IBAN in plain text. At start-up they are encrypted
 * in place, a batch at a time. A migration cannot do this, because the key is not in the database. Safe to run
 * on every start and from several instances: it only touches rows that are still plain.
 */
@Component
class IbanEncryptionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IbanEncryptionBackfill.class);
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final FieldCipher cipher;

    IbanEncryptionBackfill(JdbcClient jdbc, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        List<Row> rows;
        do {
            rows = jdbc.sql("""
                            SELECT id, merchant_id, iban FROM bank_accounts
                             WHERE iban NOT LIKE 'vault:%' AND iban NOT LIKE 'local:%'
                             LIMIT :limit
                            """)
                    .param("limit", BATCH)
                    .query((rs, n) -> new Row(rs.getString("id"), rs.getString("merchant_id"), rs.getString("iban")))
                    .list();
            for (Row row : rows) {
                jdbc.sql("UPDATE bank_accounts SET iban = :iban WHERE id = :id AND iban = :plain")
                        .param("iban", cipher.encrypt(row.iban(), row.merchantId()))
                        .param("id", row.id())
                        .param("plain", row.iban())
                        .update();
            }
            total += rows.size();
        } while (rows.size() == BATCH);
        if (total > 0) {
            log.info("Encrypted {} bank account numbers that were stored in plain text", total);
        }
    }

    private record Row(String id, String merchantId, String iban) {
    }
}
