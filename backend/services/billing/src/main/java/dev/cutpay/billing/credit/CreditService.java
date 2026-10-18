package dev.cutpay.billing.credit;

import dev.cutpay.billing.credit.CreditRepository.Balance;
import dev.cutpay.billing.credit.CreditRepository.Entry;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The credit a customer holds with a merchant. A plan change that credits more than the next invoice costs leaves the
 * rest here, and the next invoices spend it before the card is charged. All methods run inside the caller's
 * transaction, so an invoice and the credit it spent are written together or not at all.
 */
@Service
public class CreditService {

    public static final String FROM_INVOICE = "invoice_credit";
    public static final String APPLIED = "applied_to_invoice";
    public static final String RETURNED = "returned_from_voided_invoice";

    private final CreditRepository credit;
    private final Clock clock;

    public CreditService(CreditRepository credit, Clock clock) {
        this.credit = credit;
        this.clock = clock;
    }

    /** Adds credit that an invoice came to below zero. */
    public void grant(String merchantId, boolean livemode, String customerId, String currency, long amount, String invoiceId) {
        if (amount <= 0) {
            return;
        }
        var now = clock.instant();
        credit.lock(merchantId, livemode, customerId, currency, now);
        credit.add(merchantId, livemode, customerId, currency, amount, FROM_INVOICE, invoiceId, now);
    }

    /** Spends up to {@code wanted} of the customer's credit on an invoice. Returns how much was taken. */
    public long spend(String merchantId, boolean livemode, String customerId, String currency, long wanted, String invoiceId) {
        if (wanted <= 0) {
            return 0;
        }
        var now = clock.instant();
        long available = credit.lock(merchantId, livemode, customerId, currency, now);
        long taken = Math.min(available, wanted);
        if (taken > 0) {
            credit.add(merchantId, livemode, customerId, currency, -taken, APPLIED, invoiceId, now);
        }
        return taken;
    }

    /** Gives back what a voided invoice had spent. */
    public void giveBack(String merchantId, boolean livemode, String customerId, String currency, long amount, String invoiceId) {
        if (amount <= 0) {
            return;
        }
        var now = clock.instant();
        credit.lock(merchantId, livemode, customerId, currency, now);
        credit.add(merchantId, livemode, customerId, currency, amount, RETURNED, invoiceId, now);
    }

    public List<Balance> balances(String merchantId, boolean livemode, String customerId) {
        return credit.balances(merchantId, livemode, customerId);
    }

    public List<Entry> history(String merchantId, boolean livemode, String customerId) {
        return credit.entries(merchantId, livemode, customerId, 50);
    }
}
