package dev.cutpay.payouts.payout;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class PayoutDtos {

    private PayoutDtos() {
    }

    record PayoutView(String id, String object, long amount, long fee, String currency, String status, String kind,
                      LocalDate arrivalDate, String bankAccountId, String failureCode, Instant created,
                      boolean livemode) {
    }

    record ItemView(String journalEntryRef, String sourceId, long amount) {
    }

    record PayoutDetail(PayoutView payout, List<ItemView> items) {
    }

    record PayoutList(String object, List<PayoutView> data, boolean hasMore) {
    }

    /** {@code balanced} is true when the items add up to what the payout moved (amount plus fee). */
    record Reconciliation(String payoutId, long amount, long fee, long itemsTotal, int itemCount, boolean balanced) {
    }

    record CreatePayoutRequest(String currency) {
    }

    record ScheduleView(String object, String schedule, int delayDays, String weeklyAnchor) {
    }

    record UpdateScheduleRequest(String schedule, Integer delayDays, String weeklyAnchor) {
    }
}
