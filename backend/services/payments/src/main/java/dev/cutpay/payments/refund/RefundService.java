package dev.cutpay.payments.refund;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.payments.acquirer.AcquirerClient;
import dev.cutpay.payments.acquirer.AcquirerClient.RefundResult;
import dev.cutpay.payments.config.KafkaTopicsConfig;
import dev.cutpay.payments.domain.PaymentAttempt;
import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.payments.domain.Refund;
import dev.cutpay.payments.domain.Repositories.PaymentAttemptRepository;
import dev.cutpay.payments.domain.Repositories.PaymentIntentRepository;
import dev.cutpay.payments.domain.Repositories.RefundRepository;
import dev.cutpay.payments.payment.PaymentDtos.RefundView;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds follow the same shape as card confirmation: reserve the money in one short transaction (under a
 * row lock, so two refunds cannot both claim the same balance), call the acquirer outside any transaction,
 * then record the result.
 */
@Service
public class RefundService {

    public record CreateRefund(String paymentIntent, Long amount, String reason) {
    }

    private record Reserved(String refundId, String intentId, String acquirerRef, long amount) {
    }

    private final PaymentIntentRepository intents;
    private final PaymentAttemptRepository attempts;
    private final RefundRepository refunds;
    private final AcquirerClient acquirer;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public RefundService(PaymentIntentRepository intents, PaymentAttemptRepository attempts, RefundRepository refunds,
                         AcquirerClient acquirer, OutboxWriter outbox, TransactionTemplate tx) {
        this.intents = intents;
        this.attempts = attempts;
        this.refunds = refunds;
        this.acquirer = acquirer;
        this.outbox = outbox;
        this.tx = tx;
    }

    /** {@code claimedMode} is the mode an API key is limited to; null for dashboard sessions. */
    public RefundView create(String merchantId, Boolean claimedMode, CreateRefund request) {
        Reserved reserved = tx.execute(status -> reserve(merchantId, claimedMode, request));
        RefundResult result = acquirer.refund(reserved.acquirerRef(), reserved.amount());
        Refund refund = tx.execute(status -> settle(reserved, result));
        return RefundView.of(refund);
    }

    @Transactional(readOnly = true)
    public RefundPage list(String merchantId, boolean livemode, String paymentId, String startingAfter, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        // One more than asked for, to learn whether there is a next page.
        var found = refunds.page(merchantId, livemode, paymentId, startingAfter, PageRequest.of(0, size + 1));
        return new RefundPage("list", found.stream().limit(size).map(RefundView::of).toList(), found.size() > size);
    }

    public record RefundPage(String object, List<RefundView> data, boolean hasMore) {
    }

    private Reserved reserve(String merchantId, Boolean claimedMode, CreateRefund request) {
        var locked = claimedMode == null ? intents.lockByIdAndMerchantId(request.paymentIntent(), merchantId)
                : intents.lockByIdAndMerchantIdAndLivemode(request.paymentIntent(), merchantId, claimedMode);
        PaymentIntent intent = locked
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No such payment"));
        if (intent.getStatus() != dev.cutpay.payments.domain.PaymentStatus.SUCCEEDED
                && intent.getStatus() != dev.cutpay.payments.domain.PaymentStatus.PARTIALLY_REFUNDED) {
            throw ApiException.invalidRequest("payment_not_refundable",
                    "Only succeeded payments with an amount left can be refunded", "payment_intent");
        }
        long amount = request.amount() != null ? request.amount() : intent.refundable();
        if (amount <= 0) {
            throw ApiException.invalidRequest("invalid_amount", "Refund amount must be positive", "amount");
        }
        if (amount > intent.refundable()) {
            throw ApiException.invalidRequest("refund_exceeds_charge",
                    "Refund is larger than the amount still available (%d)".formatted(intent.refundable()), "amount");
        }
        String acquirerRef = attempts.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()).stream()
                .filter(a -> "succeeded".equals(a.getStatus()))
                .map(PaymentAttempt::getAcquirerRef)
                .reduce((first, second) -> second)
                .orElseThrow(() -> ApiException.invalidRequest("payment_not_refundable",
                        "The payment has no successful charge", "payment_intent"));

        var refund = new Refund(Ids.generate("re"), intent.getId(), merchantId, amount, intent.getCurrency(),
                request.reason());
        refunds.save(refund);
        intent.reserveRefund(amount);
        publish("RefundCreated", refund, intent);
        return new Reserved(refund.getId(), intent.getId(), acquirerRef, amount);
    }

    private Refund settle(Reserved reserved, RefundResult result) {
        PaymentIntent intent = intents.lockById(reserved.intentId()).orElseThrow();
        Refund refund = refunds.findById(reserved.refundId()).orElseThrow();
        switch (result) {
            case RefundResult.Succeeded succeeded -> {
                refund.succeeded(succeeded.refundRef());
                refunds.flush();
                intent.applyRefundedTotal(refunds.succeededTotal(intent.getId()));
                publish("RefundSucceeded", refund, intent);
            }
            case RefundResult.Failed failed -> {
                refund.failed(failed.code());
                intent.releaseRefund(refund.getAmount());
                publish("RefundFailed", refund, intent);
            }
        }
        return refund;
    }

    private void publish(String eventType, Refund refund, PaymentIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refund_id", refund.getId());
        payload.put("payment_id", intent.getId());
        payload.put("amount", refund.getAmount());
        payload.put("currency", refund.getCurrency());
        payload.put("status", refund.getStatus());
        payload.put("failure_code", refund.getFailureCode());
        outbox.write(KafkaTopicsConfig.REFUND_TOPIC, eventType, refund.getId(), intent.getMerchantId(),
                intent.isLivemode(), payload);
    }
}
