package dev.cutpay.webhooks.delivery;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/**
 * Where webhooks may be sent. Test mode allows plain http and localhost, because that is where a developer
 * machine is. Live mode needs https and refuses internal addresses, so an endpoint cannot be used to make
 * the platform call services on its own network.
 */
public final class UrlPolicy {

    private UrlPolicy() {
    }

    /** Checks a URL as typed (no DNS). Returns the problem, or empty when it is acceptable. */
    public static Optional<String> problemWith(String url, boolean livemode) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Optional.of("The URL is not valid");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            return Optional.of("The URL must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            return Optional.of("The URL must have a host and no credentials");
        }
        if (livemode && !scheme.equals("https")) {
            return Optional.of("Live mode endpoints must use https");
        }
        if (livemode && isInternalLiteral(uri.getHost())) {
            return Optional.of("Live mode endpoints cannot point at internal addresses");
        }
        return Optional.empty();
    }

    /** At delivery time the host is resolved, so a name that now points inside the network is caught too. */
    public static boolean resolvesToInternalAddress(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isInternal(address)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false; // the request will fail on its own
        }
    }

    private static boolean isInternalLiteral(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".internal")
                || lower.endsWith(".local")) {
            return true;
        }
        // Only literal addresses are parsed here; names are resolved at delivery time.
        if (lower.matches("[0-9.]+") || lower.contains(":")) {
            try {
                return isInternal(InetAddress.getByName(lower));
            } catch (UnknownHostException e) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInternal(InetAddress a) {
        return a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || a.isMulticastAddress();
    }
}
