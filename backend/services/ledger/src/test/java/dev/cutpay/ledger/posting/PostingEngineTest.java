package dev.cutpay.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cutpay.ledger.posting.PostingEngine.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostingEngineTest {

    @Test
    void mergesLinesOnTheSameAccount() {
        List<Line> merged = PostingEngine.consolidate(List.of(new Line("acc_b", 70), new Line("acc_a", -100),
                new Line("acc_b", 30)));

        assertThat(merged).containsExactly(new Line("acc_a", -100), new Line("acc_b", 100));
    }

    @Test
    void dropsLinesThatCancelOutOrAreZero() {
        List<Line> merged = PostingEngine.consolidate(List.of(new Line("acc_a", 0), new Line("acc_b", 5),
                new Line("acc_b", -5), new Line("acc_c", 12), new Line("acc_d", -12)));

        assertThat(merged).containsExactly(new Line("acc_c", 12), new Line("acc_d", -12));
    }

    @Test
    void sortsByAccountSoConcurrentEntriesLockBalancesInTheSameOrder() {
        List<Line> merged = PostingEngine.consolidate(List.of(new Line("acc_z", 1), new Line("acc_m", 1),
                new Line("acc_a", -2)));

        assertThat(merged).extracting(Line::accountId).containsExactly("acc_a", "acc_m", "acc_z");
    }

    @Test
    void keepsTheTotalOfAnEntryUnchanged() {
        List<Line> lines = List.of(new Line("acc_a", 10_000), new Line("acc_b", -9_861), new Line("acc_c", -139),
                new Line("acc_c", 0));

        assertThat(PostingEngine.consolidate(lines).stream().mapToLong(Line::amount).sum()).isZero();
    }
}
