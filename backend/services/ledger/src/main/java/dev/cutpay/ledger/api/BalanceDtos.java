package dev.cutpay.ledger.api;

import java.time.Instant;
import java.util.List;

final class BalanceDtos {

    private BalanceDtos() {
    }

    record AmountView(long amount, String currency) {
    }

    /** Merchant balances per currency. Amounts are what the platform owes the merchant. */
    record BalanceView(String object, boolean livemode, List<AmountView> pending, List<AmountView> available,
                       List<AmountView> reserved) {
    }

    record TransactionView(String id, String object, String type, String sourceId, String parentId, String currency,
                           long gross, long fee, long net, String description, Instant availableOn, Instant created,
                           boolean livemode) {
    }

    record TransactionList(String object, List<TransactionView> data, boolean hasMore) {
    }

    record DayView(String date, String currency, long volume, long fees, long net) {
    }

    record SummaryView(String object, List<DayView> data) {
    }

    record CurrencyTotal(String currency, long total) {
    }

    record TrialBalanceView(boolean balanced, long total, long entries, long postings, long balanceDrift,
                            List<CurrencyTotal> byCurrency) {
    }
}
