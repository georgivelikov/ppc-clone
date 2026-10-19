package dev.cutpay.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cutpay.reporting.config.ReportingProperties;
import dev.cutpay.reporting.run.ReportCatalog.Column;
import dev.cutpay.reporting.run.ReportWriter;
import dev.cutpay.reporting.run.SignedLinks;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ReportWriterTest {

    private static final List<Column> COLUMNS = List.of(new Column("id", "Payment"), new Column("note", "Note"), new Column("amount", "Amount"));

    private static Map<String, Object> row(String id, String note, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("note", note);
        m.put("amount", amount);
        return m;
    }

    private static String text(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8).substring(1); // without the byte order mark
    }

    @Test
    void csvQuotesCommasQuotesAndNewlines() {
        String out = text(ReportWriter.csv(COLUMNS, List.of(row("pay_1", "a, \"b\"\nc", "12.34"))));
        assertThat(out).isEqualTo("Payment,Note,Amount\r\npay_1,\"a, \"\"b\"\"\nc\",12.34\r\n");
    }

    @Test
    void aValueThatCouldRunAsAFormulaGetsAnApostrophe() {
        String out = text(ReportWriter.csv(COLUMNS, List.of(
                row("pay_1", "=HYPERLINK(\"http://evil\")", "-5.00"),
                row("pay_2", "+1+1", "0.00"),
                row("pay_3", "@SUM(A1)", "12.00"),
                row("pay_4", "-cmd|' /C calc'!A0", "1.00"))));
        assertThat(out).contains("'=HYPERLINK").contains("'+1+1").contains("'@SUM").contains("'-cmd");
        // A negative amount is a number, not a formula.
        assertThat(out).contains(",-5.00\r\n").doesNotContain("'-5.00");
    }

    @Test
    void anXlsxFileIsAZipWithOneSheetOfTheRowsAndNoLiveFormulas() throws Exception {
        byte[] file = ReportWriter.xlsx("Fees <2026>", COLUMNS, List.of(row("pay_1", "=1+1", "12.34"), row("pay_2", "a & b", "-3")));
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml");
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("<c r=\"A1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Payment</t></is></c>")
                .contains("<c r=\"C2\"><v>12.34</v></c>")      // a number stays a number
                .contains("<c r=\"C3\"><v>-3</v></c>")
                .contains("'=1+1")                                // text that looks like a formula is quoted
                .contains("a &amp; b")
                .doesNotContain("<f>");
        assertThat(parts.get("xl/workbook.xml")).contains("Fees &lt;2026&gt;").doesNotContain("<2026>");
    }

    @Test
    void columnLettersRunPastZ() {
        assertThat(java.util.stream.IntStream.of(0, 25, 26, 27, 701, 702).mapToObj(i -> {
            try {
                var m = ReportWriter.class.getDeclaredMethod("column", int.class);
                m.setAccessible(true);
                return (String) m.invoke(null, i);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }).toList()).containsExactly("A", "Z", "AA", "AB", "ZZ", "AAA");
    }

    @Test
    void signedLinksCarryTheirOwnExpiryAndBreakWhenChanged() {
        var links = new SignedLinks(new ReportingProperties("secret-one", Duration.ofMinutes(15), Duration.ofSeconds(2), "http://x"));
        var link = links.create("rpt_1");
        String signature = link.path().substring(link.path().indexOf("signature=") + 10);

        assertThat(links.valid("rpt_1", link.expiresAt(), signature)).isTrue();
        assertThat(links.valid("rpt_2", link.expiresAt(), signature)).isFalse();             // another report
        assertThat(links.valid("rpt_1", link.expiresAt() + 3600, signature)).isFalse();      // a longer life
        assertThat(links.valid("rpt_1", link.expiresAt(), signature.replace('a', 'b'))).isFalse().as("tampered");
        assertThat(links.valid("rpt_1", link.expiresAt(), null)).isFalse();
        // Another secret signs differently, and an expired link is refused even with a good signature.
        var other = new SignedLinks(new ReportingProperties("secret-two", Duration.ofMinutes(15), Duration.ofSeconds(2), "http://x"));
        assertThat(other.valid("rpt_1", link.expiresAt(), signature)).isFalse();
        var expired = new SignedLinks(new ReportingProperties("secret-one", Duration.ofSeconds(-10), Duration.ofSeconds(2), "http://x"));
        var old = expired.create("rpt_1");
        assertThat(expired.valid("rpt_1", old.expiresAt(), old.path().substring(old.path().indexOf("signature=") + 10))).isFalse();
    }
}
