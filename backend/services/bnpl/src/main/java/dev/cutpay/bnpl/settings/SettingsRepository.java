package dev.cutpay.bnpl.settings;

import static dev.cutpay.bnpl.config.Sql.ts;

import dev.cutpay.bnpl.routing.MerchantRouting;
import dev.cutpay.bnpl.routing.MerchantRouting.ProviderSetting;
import dev.cutpay.bnpl.routing.MerchantRouting.Weights;
import dev.cutpay.bnpl.routing.RoutingMode;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {

    private final JdbcClient jdbc;

    SettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What the merchant chose, completed with the defaults for everything they have not. */
    public MerchantRouting load(String merchantId, boolean livemode) {
        List<ProviderSetting> providers = jdbc.sql("""
                        SELECT provider_code, enabled, priority FROM merchant_bnpl_settings
                         WHERE merchant_id = :merchant AND livemode = :livemode
                        """)
                .param("merchant", merchantId).param("livemode", livemode)
                .query((rs, n) -> new ProviderSetting(rs.getString("provider_code"), rs.getBoolean("enabled"), rs.getInt("priority")))
                .list();
        var routing = jdbc.sql("""
                        SELECT routing_mode, weight_cost, weight_speed, weight_approval FROM merchant_routing
                         WHERE merchant_id = :merchant AND livemode = :livemode
                        """)
                .param("merchant", merchantId).param("livemode", livemode)
                .query((rs, n) -> MerchantRouting.withSettings(RoutingMode.fromWire(rs.getString("routing_mode")).orElseThrow(),
                        new Weights(rs.getInt("weight_cost"), rs.getInt("weight_speed"), rs.getInt("weight_approval")), providers))
                .optional();
        return routing.orElseGet(() -> MerchantRouting.withSettings(RoutingMode.CUSTOMER_CHOICE, Weights.EQUAL, providers));
    }

    public void save(String merchantId, boolean livemode, MerchantRouting routing, Instant now) {
        jdbc.sql("""
                        INSERT INTO merchant_routing (merchant_id, livemode, routing_mode, weight_cost, weight_speed,
                                                      weight_approval, updated_at)
                        VALUES (:merchant, :livemode, :mode, :cost, :speed, :approval, :now)
                        ON CONFLICT (merchant_id, livemode) DO UPDATE SET routing_mode = :mode, weight_cost = :cost,
                            weight_speed = :speed, weight_approval = :approval, updated_at = :now
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("mode", routing.mode().wire())
                .param("cost", routing.weights().cost()).param("speed", routing.weights().speed())
                .param("approval", routing.weights().approval()).param("now", ts(now)).update();
        for (ProviderSetting setting : routing.providers()) {
            jdbc.sql("""
                            INSERT INTO merchant_bnpl_settings (merchant_id, livemode, provider_code, enabled, priority, updated_at)
                            VALUES (:merchant, :livemode, :code, :enabled, :priority, :now)
                            ON CONFLICT (merchant_id, livemode, provider_code) DO UPDATE SET enabled = :enabled,
                                priority = :priority, updated_at = :now
                            """)
                    .param("merchant", merchantId).param("livemode", livemode).param("code", setting.code())
                    .param("enabled", setting.enabled()).param("priority", setting.priority()).param("now", ts(now)).update();
        }
    }
}
