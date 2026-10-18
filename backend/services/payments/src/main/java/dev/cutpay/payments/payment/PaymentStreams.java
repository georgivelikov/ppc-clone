package dev.cutpay.payments.payment;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes payment status changes to screens that are watching one payment over Server-Sent Events (the
 * "charge with QR" page). The subscribers live in this instance's memory: a change made by this instance
 * is announced right after its transaction commits, and a change made by another instance arrives through
 * {@link PaymentStreamListener}. A page that connects mid-way gets the current status first, so nothing is
 * missed between "show QR" and "subscribe".
 */
@Component
public class PaymentStreams {

    private static final Logger log = LoggerFactory.getLogger(PaymentStreams.class);
    private static final long TIMEOUT_MS = 10 * 60_000L;
    private static final Set<String> FINAL = Set.of("succeeded", "failed", "canceled", "refunded", "requires_capture");

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** Opens a stream that starts with {@code currentStatus} and then follows the payment. */
    public SseEmitter subscribe(String paymentId, String currentStatus, String currentFailureCode) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> set = subscribers.computeIfAbsent(paymentId, id -> new CopyOnWriteArraySet<>());
        set.add(emitter);
        Runnable cleanup = () -> {
            set.remove(emitter);
            subscribers.computeIfPresent(paymentId, (id, s) -> s.isEmpty() ? null : s);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> cleanup.run());
        send(paymentId, emitter, currentStatus, currentFailureCode);
        return emitter;
    }

    /** Announces a status once the surrounding transaction has committed (or at once when there is none). */
    public void publishAfterCommit(String paymentId, String status, String failureCode) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(paymentId, status, failureCode);
                }
            });
        } else {
            publish(paymentId, status, failureCode);
        }
    }

    public void publish(String paymentId, String status, String failureCode) {
        Set<SseEmitter> set = subscribers.get(paymentId);
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            send(paymentId, emitter, status, failureCode);
        }
    }

    int subscriberCount(String paymentId) {
        Set<SseEmitter> set = subscribers.get(paymentId);
        return set == null ? 0 : set.size();
    }

    private void send(String paymentId, SseEmitter emitter, String status, String failureCode) {
        try {
            String data = failureCode == null ? "{\"status\":\"%s\"}".formatted(status)
                    : "{\"status\":\"%s\",\"failure_code\":\"%s\"}".formatted(status, failureCode);
            emitter.send(SseEmitter.event().name("status").data(data));
            if (FINAL.contains(status)) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping a closed stream for {}", paymentId);
            emitter.completeWithError(e);
        }
    }
}
