package dev.cutpay.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.notifications.log.NotificationService;
import dev.cutpay.notifications.mail.MailSender;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Emails are caught by a fake sender, so the tests can also make the mail server fail. */
@SpringBootTest(properties = {
        "cutpay.notifications.retry-poll-interval=PT1H",
        "cutpay.notifications.retry-base-delay=PT0S",
        "cutpay.notifications.max-attempts=3"})
@AutoConfigureMockMvc
@Testcontainers
class NotificationsIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    static class FakeMail implements MailSender {
        final List<Mail> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public void send(Mail mail) throws IOException {
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IOException("mail server is down");
            }
            sent.add(mail);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeMail fakeMail() {
            return new FakeMail();
        }
    }

    @Autowired
    FakeMail mail;

    @Autowired
    NotificationService notifications;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
        mail.sent.clear();
        mail.failuresLeft.set(0);
    }

    private static String envelope(String id, String type, String merchantId, String aggregate, String payload) {
        return "{\"event_id\":\"%s\",\"event_type\":\"%s\",\"aggregate_id\":\"%s\",\"merchant_id\":%s,\"version\":1,\"payload\":%s}"
                .formatted(id, type, aggregate, merchantId == null ? "null" : "\"" + merchantId + "\"", payload);
    }

    private void publish(String topic, String key, String message) {
        kafka.send(topic, key, message);
    }

    @Test
    void anEmailRequestIsRenderedInTheRecipientsLanguageAndSent() {
        publish("notification.events", "a", envelope("evt_req1", "EmailRequested", null, "a", """
                {"to":"ivan@example.com","template":"password_reset","locale":"bg","data":{"reset_url":"https://cutpay.test/r?token=1"}}"""));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(mail.sent).hasSize(1));
        assertThat(mail.sent.get(0).to()).isEqualTo("ivan@example.com");
        assertThat(mail.sent.get(0).subject()).isEqualTo("Нова парола за Cutpay");
        assertThat(mail.sent.get(0).html()).contains("https://cutpay.test/r?token=1");
        assertThat(jdbc.sql("SELECT status FROM notification_log WHERE event_id = 'evt_req1'").query(String.class).single())
                .isEqualTo("sent");
    }

    @Test
    void aRedeliveredRequestSendsNothingTwice() {
        String message = envelope("evt_dup", "EmailRequested", null, "a", """
                {"to":"dup@example.com","template":"email_verification","data":{"verify_url":"https://cutpay.test/v?token=1"}}""");
        publish("notification.events", "a", message);
        publish("notification.events", "a", message);
        publish("notification.events", "a", envelope("evt_dup_end", "EmailRequested", null, "a", """
                {"to":"end@example.com","template":"email_verification","data":{"verify_url":"https://cutpay.test/v?token=2"}}"""));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(mail.sent).extracting(MailSender.Mail::to).contains("end@example.com"));
        assertThat(mail.sent).extracting(MailSender.Mail::to).filteredOn("dup@example.com"::equals).hasSize(1);
    }

    @Test
    void aFailingMailServerIsRetriedWithBackoffAndGivesUpAfterTheLimit() {
        mail.failuresLeft.set(2);
        notifications.handle(new NotificationService.Request("evt_retry", null, "retry@example.com", null, "email_verification",
                "en", Map.of("verify_url", "https://cutpay.test/v?token=3")));
        assertThat(mail.sent).isEmpty();
        assertThat(logStatus("evt_retry")).isEqualTo("failed");

        notifications.deliverDue(); // second failure
        assertThat(logStatus("evt_retry")).isEqualTo("failed");
        assertThat(notifications.deliverDue()).isEqualTo(1); // third attempt works
        assertThat(logStatus("evt_retry")).isEqualTo("sent");
        assertThat(attempts("evt_retry")).isEqualTo(3);

        mail.failuresLeft.set(10);
        notifications.handle(new NotificationService.Request("evt_dead", null, "dead@example.com", null, "email_verification",
                "en", Map.of("verify_url", "https://cutpay.test/v?token=4")));
        notifications.deliverDue();
        notifications.deliverDue();
        assertThat(logStatus("evt_dead")).isEqualTo("dead");
        assertThat(notifications.deliverDue()).isZero();
    }

    @Test
    void teamEventsBuildTheContactListAndPayoutsReachOwnersAndAdminsOnly() {
        publish("merchant.events", "m1", envelope("evt_m1", "MerchantCreated", "mer_1", "mer_1", """
                {"merchant_id":"mer_1","legal_name":"Rose Cafe","owner_user_id":"usr_1","owner_email":"owner@example.com","owner_name":"Olga"}"""));
        publish("merchant.events", "m1", envelope("evt_m2", "TeamMemberJoined", "mer_1", "mer_1", """
                {"user_id":"usr_2","email":"viewer@example.com","full_name":"Vera","role":"viewer"}"""));
        publish("merchant.events", "m1", envelope("evt_m3", "TeamMemberJoined", "mer_1", "mer_1", """
                {"user_id":"usr_3","email":"admin@example.com","full_name":"Ada","role":"admin"}"""));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT count(*) FROM merchant_contacts WHERE merchant_id = 'mer_1'").query(Integer.class).single()).isEqualTo(3));

        publish("payout.events", "p1", envelope("evt_p1", "PayoutPaid", "mer_1", "po_1", """
                {"amount":12345,"currency":"EUR","arrival_date":"2026-10-22"}"""));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(mail.sent).hasSize(2));
        assertThat(mail.sent).extracting(MailSender.Mail::to).containsExactlyInAnyOrder("owner@example.com", "admin@example.com");
        assertThat(mail.sent.get(0).subject()).isEqualTo("Payout of 123.45 EUR is on its way");

        // A removed member stops receiving.
        publish("merchant.events", "m1", envelope("evt_m4", "TeamMemberRemoved", "mer_1", "mer_1", "{\"user_id\":\"usr_3\"}"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT count(*) FROM merchant_contacts WHERE merchant_id = 'mer_1'").query(Integer.class).single()).isEqualTo(2));
    }

    @Test
    void aPaymentSendsTheCustomerAReceiptAndTheNewPaymentEmailIsOffUntilChosen() throws Exception {
        notifications.upsertContact("mer_2", "usr_9", "owner2@example.com", "Olga", "owner", "en");
        notifications.rememberMerchant("mer_2", "Sofa Studio");
        publish("payment.events", "pay_1", envelope("evt_pay1", "PaymentSucceeded", "mer_2", "pay_1", """
                {"amount":2500,"currency":"EUR","customer_email":"anna@example.com","locale":"bg"}"""));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(mail.sent).hasSize(1));
        assertThat(mail.sent.get(0).to()).isEqualTo("anna@example.com");
        assertThat(mail.sent.get(0).subject()).isEqualTo("Разписка от Sofa Studio за 25.00 EUR");
        // The owner's "new payment" email was written to the log as skipped, not sent.
        assertThat(jdbc.sql("SELECT status FROM notification_log WHERE recipient = 'owner2@example.com' AND template = 'payment_new'")
                .query(String.class).single()).isEqualTo("skipped");

        // The owner opts in; the next payment is mailed.
        mvc.perform(put("/v1/notification_preferences").with(jwt().jwt(j -> j.claim("mid", "mer_2").claim("email", "owner2@example.com")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"template\":\"payment_new\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.template=='payment_new')].enabled").value(true));
        mail.sent.clear();
        publish("payment.events", "pay_2", envelope("evt_pay2", "PaymentSucceeded", "mer_2", "pay_2", """
                {"amount":900,"currency":"EUR"}"""));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(mail.sent).hasSize(1));
        assertThat(mail.sent.get(0).to()).isEqualTo("owner2@example.com");
        assertThat(mail.sent.get(0).subject()).isEqualTo("New payment of 9.00 EUR");

        mvc.perform(get("/v1/notifications").with(jwt().jwt(j -> j.claim("mid", "mer_2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void anEmailThatCannotBeSwitchedOffIsRefused() throws Exception {
        mvc.perform(put("/v1/notification_preferences").with(jwt().jwt(j -> j.claim("mid", "mer_3").claim("email", "x@example.com")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"template\":\"password_reset\",\"enabled\":false}"))
                .andExpect(status().isBadRequest());
    }

    private String logStatus(String eventId) {
        return jdbc.sql("SELECT status FROM notification_log WHERE event_id = :e").param("e", eventId).query(String.class).single();
    }

    private int attempts(String eventId) {
        return jdbc.sql("SELECT attempts FROM notification_log WHERE event_id = :e").param("e", eventId).query(Integer.class).single();
    }
}
