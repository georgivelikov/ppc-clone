package dev.cutpay.ledger.posting;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.ledger.config.Topics;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every line of the merchant's balance history, announced as it is written. The reporting service builds its numbers
 * from these, so a report and the ledger cannot disagree about what a payment cost.
 */
public final class BalanceTransactionEvent {

    public static final String TYPE = "BalanceTransactionRecorded";

    private BalanceTransactionEvent() {
    }

    public static void emit(OutboxWriter outbox, String id, String merchantId, boolean livemode, String currency, String type,
                            String sourceId, String parentId, long gross, long fee, long net, String description,
                            Instant createdAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transaction_id", id);
        payload.put("type", type);
        payload.put("source_id", sourceId);
        payload.put("parent_id", parentId);
        payload.put("currency", currency);
        payload.put("gross", gross);
        payload.put("fee", fee);
        payload.put("net", net);
        payload.put("description", description);
        payload.put("created_at", createdAt.toString());
        outbox.write(Topics.LEDGER_ENTRIES, TYPE, id, merchantId, livemode, payload);
    }
}
