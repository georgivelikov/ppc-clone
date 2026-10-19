package dev.cutpay.payments.payment;

import dev.cutpay.web.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which ways of paying a merchant offers at the checkout. Everything the platform supports is on until the merchant
 * switches it off; cards cannot be switched off, so a checkout always has a way to pay. Instalments are chosen in the
 * instalments settings, not here.
 */
@Service
public class PaymentMethodSettings {

    /** What a merchant can switch off. */
    public static final List<String> CONFIGURABLE = List.of(PaymentMethodRules.APPLE_PAY, PaymentMethodRules.GOOGLE_PAY,
            PaymentMethodRules.BLIK);

    public record Setting(String method, boolean enabled, boolean locked) {
    }

    private final JdbcClient jdbc;

    public PaymentMethodSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Set<String> disabled(String merchantId) {
        return new HashSet<>(jdbc.sql("SELECT method FROM payment_method_settings WHERE merchant_id = :merchant AND NOT enabled")
                .param("merchant", merchantId).query(String.class).list());
    }

    @Transactional(readOnly = true)
    public boolean enabled(String merchantId, String method) {
        return !disabled(merchantId).contains(method);
    }

    @Transactional(readOnly = true)
    public List<Setting> list(String merchantId) {
        Set<String> off = disabled(merchantId);
        List<Setting> settings = new ArrayList<>();
        settings.add(new Setting(PaymentMethodRules.CARD, true, true));
        for (String method : CONFIGURABLE) {
            settings.add(new Setting(method, !off.contains(method), false));
        }
        return settings;
    }

    @Transactional
    public List<Setting> update(String merchantId, Map<String, Boolean> changes) {
        Map<String, Boolean> checked = new LinkedHashMap<>();
        for (var change : changes.entrySet()) {
            boolean card = PaymentMethodRules.CARD.equals(change.getKey());
            if (card && !Boolean.TRUE.equals(change.getValue())) {
                throw ApiException.invalidRequest("card_cannot_be_disabled", "Cards cannot be switched off", "methods");
            }
            if (!card && !CONFIGURABLE.contains(change.getKey())) {
                throw ApiException.invalidRequest("unknown_method", "Unknown payment method " + change.getKey(), "methods");
            }
            if (!card) {
                checked.put(change.getKey(), change.getValue());
            }
        }
        for (var change : checked.entrySet()) {
            jdbc.sql("""
                            INSERT INTO payment_method_settings (merchant_id, method, enabled)
                            VALUES (:merchant, :method, :enabled)
                            ON CONFLICT (merchant_id, method) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
                            """)
                    .param("merchant", merchantId).param("method", change.getKey())
                    .param("enabled", Boolean.TRUE.equals(change.getValue())).update();
        }
        return list(merchantId);
    }
}
