package dev.cutpay.notifications.mail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Subjects and bodies by template and language, from {@code mail/<language>.properties}. A message uses
 * {@code {{name}}} for a value from the request, which is escaped for HTML; a language without the template falls
 * back to English. Values are never trusted: a customer's name can contain markup.
 */
@Component
public class Templates {

    public static final Set<String> LANGUAGES = Set.of("en", "bg");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");

    private final Map<String, Properties> byLanguage = new HashMap<>();

    public Templates() {
        for (String language : LANGUAGES) {
            Properties properties = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/mail/" + language + ".properties")) {
                if (in == null) {
                    throw new IllegalStateException("Missing mail templates for " + language);
                }
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            byLanguage.put(language, properties);
        }
    }

    public boolean exists(String template) {
        return byLanguage.get("en").containsKey(template + ".subject");
    }

    public record Rendered(String subject, String html) {
    }

    public Rendered render(String template, String locale, Map<String, ?> values) {
        String language = LANGUAGES.contains(locale) ? locale : "en";
        Properties properties = byLanguage.get(language).containsKey(template + ".subject")
                ? byLanguage.get(language) : byLanguage.get("en");
        String subject = fill(properties.getProperty(template + ".subject"), values, false);
        String body = fill(properties.getProperty(template + ".body"), values, true);
        String footer = properties.getProperty("footer", byLanguage.get("en").getProperty("footer"));
        return new Rendered(subject, layout(body, footer));
    }

    private static String fill(String text, Map<String, ?> values, boolean html) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = values.get(matcher.group(1));
            String shown = value == null ? "" : String.valueOf(value);
            matcher.appendReplacement(out, Matcher.quoteReplacement(html ? escape(shown) : shown));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** A link written as {@code [text](url)} becomes an anchor; only http(s) addresses qualify. */
    private static String layout(String body, String footer) {
        String withLinks = body.replaceAll("\\[([^]]+)]\\((https?://[^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        String paragraphs = "<p>" + withLinks.replace("\n\n", "</p><p>").replace("\n", "<br>") + "</p>";
        return "<div style=\"font-family:system-ui,sans-serif;max-width:560px;margin:auto;color:#1c2330\">"
                + "<h2 style=\"color:#2f4bd0\">Cutpay</h2>" + paragraphs
                + "<hr style=\"border:none;border-top:1px solid #e3e7ee\"><p style=\"color:#667085;font-size:12px\">"
                + footer + "</p></div>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("[", "&#91;").replace("(", "&#40;");
    }
}
