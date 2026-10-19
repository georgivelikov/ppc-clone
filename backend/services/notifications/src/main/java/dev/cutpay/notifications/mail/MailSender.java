package dev.cutpay.notifications.mail;

import java.io.IOException;

/** Hands one finished email to whatever delivers it. Throws when it could not be delivered, so it can be retried. */
public interface MailSender {

    record Mail(String to, String subject, String html) {
    }

    void send(Mail mail) throws IOException;
}
