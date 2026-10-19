package dev.cutpay.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cutpay.notifications.mail.Templates;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplatesTest {

    private final Templates templates = new Templates();

    @Test
    void fillsValuesAndTurnsLinksIntoAnchors() {
        var mail = templates.render("password_reset", "en", Map.of("reset_url", "https://cutpay.test/reset?token=abc&x=1"));
        assertThat(mail.subject()).isEqualTo("Reset your Cutpay password");
        assertThat(mail.html()).contains("<a href=\"https://cutpay.test/reset?token=abc&amp;x=1\">Choose a new password</a>");
    }

    @Test
    void speaksBulgarianAndFallsBackToEnglish() {
        assertThat(templates.render("payout_paid", "bg", Map.of("amount", "10.00", "currency", "EUR", "arrival", "утре",
                "payout_url", "https://x.test/p")).subject()).isEqualTo("Изплащане от 10.00 EUR е на път");
        assertThat(templates.render("payout_paid", "ro", Map.of("amount", "10.00", "currency", "EUR", "arrival", "x",
                "payout_url", "https://x.test/p")).subject()).isEqualTo("Payout of 10.00 EUR is on its way");
    }

    @Test
    void valuesAreEscapedSoACustomerNameCannotInjectMarkup() {
        var mail = templates.render("payment_receipt", "en", Map.of("merchant_name", "<script>alert(1)</script>",
                "amount", "5.00", "currency", "EUR", "payment_id", "pay_1"));
        assertThat(mail.html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void aValueCannotSmuggleInALink() {
        var mail = templates.render("payment_receipt", "en", Map.of("merchant_name", "[Click](https://evil.test)",
                "amount", "5.00", "currency", "EUR", "payment_id", "pay_1"));
        assertThat(mail.html()).doesNotContain("href=\"https://evil.test\"");
    }

    @Test
    void everyEnglishTemplateHasABulgarianOne() {
        for (String name : new String[] {"team_invite", "password_reset", "email_verification", "kyc_approved", "kyc_rejected",
                "kyc_needs_info", "dispute_opened", "dispute_closed", "payout_paid", "payment_receipt", "payment_new", "report_ready"}) {
            assertThat(templates.exists(name)).as(name).isTrue();
            var en = templates.render(name, "en", Map.of());
            var bg = templates.render(name, "bg", Map.of());
            assertThat(bg.subject()).as(name + " in Bulgarian").isNotEqualTo(en.subject());
        }
    }
}
