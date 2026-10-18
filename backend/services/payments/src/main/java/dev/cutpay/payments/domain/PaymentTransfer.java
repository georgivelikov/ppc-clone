package dev.cutpay.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Part of a payment that goes to a connected account once the payment succeeds. */
@Entity
@Table(name = "payment_transfers")
public class PaymentTransfer {

    @Id
    private String id;

    @Column(name = "payment_intent_id", nullable = false, updatable = false)
    private String paymentIntentId;

    @Column(nullable = false, updatable = false)
    private String destination;

    @Column(nullable = false, updatable = false)
    private long amount;

    protected PaymentTransfer() {
    }

    public PaymentTransfer(String id, String paymentIntentId, String destination, long amount) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.destination = destination;
        this.amount = amount;
    }

    public String getId() {
        return id;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public String getDestination() {
        return destination;
    }

    public long getAmount() {
        return amount;
    }
}
