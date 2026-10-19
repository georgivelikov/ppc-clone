package dev.cutpay.notifications.mail;

import dev.cutpay.notifications.config.NotificationProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Talks SMTP to the local Mailpit by hand, which is a good way to see how little the protocol is: a greeting, the
 * sender, the recipient, the message, goodbye. No encryption or login, because Mailpit wants neither; a real
 * provider would need both, and a library.
 */
@Component
public class SmtpMailSender implements MailSender {

    private static final int TIMEOUT_MS = 5_000;

    private final NotificationProperties properties;

    public SmtpMailSender(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(Mail mail) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.smtpHost(), properties.smtpPort()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);

            expect(in, "220");
            command(out, in, "EHLO cutpay.test", "250");
            command(out, in, "MAIL FROM:<" + address(properties.from()) + ">", "250");
            command(out, in, "RCPT TO:<" + mail.to() + ">", "250");
            command(out, in, "DATA", "354");
            out.write(message(mail));
            out.write("\r\n.\r\n");
            out.flush();
            expect(in, "250");
            command(out, in, "QUIT", "221");
        }
    }

    private String message(Mail mail) {
        String body = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(mail.html().getBytes(StandardCharsets.UTF_8));
        return "From: " + properties.from() + "\r\n"
                + "To: " + mail.to() + "\r\n"
                + "Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(mail.subject().getBytes(StandardCharsets.UTF_8)) + "?=\r\n"
                + "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\n"
                + "Message-ID: <" + UUID.randomUUID() + "@cutpay.test>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + body;
    }

    private static void command(Writer out, BufferedReader in, String line, String expected) throws IOException {
        out.write(line + "\r\n");
        out.flush();
        expect(in, expected);
    }

    /** Replies can span lines ("250-..." continues, "250 ..." ends); only the last line carries the verdict. */
    private static void expect(BufferedReader in, String code) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("The mail server closed the connection");
            }
        } while (line.length() > 3 && line.charAt(3) == '-');
        if (!line.startsWith(code)) {
            throw new IOException("The mail server answered '" + line + "', expected " + code);
        }
    }

    /** "Cutpay <no-reply@cutpay.test>" becomes "no-reply@cutpay.test". */
    private static String address(String from) {
        int open = from.indexOf('<');
        return open < 0 ? from : from.substring(open + 1, from.indexOf('>'));
    }
}
