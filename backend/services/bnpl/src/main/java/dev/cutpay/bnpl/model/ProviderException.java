package dev.cutpay.bnpl.model;

/** A provider could not do what was asked. The kind says whether trying another provider may help. */
public class ProviderException extends RuntimeException {

    public enum Kind {
        /** No answer in time. */
        TIMEOUT,
        /** The provider is down or failing. */
        UNAVAILABLE,
        /** The provider answered and said no, for example an amount outside its limits. */
        REFUSED,
        /** The answer did not look like what the provider promises. */
        INVALID_RESPONSE
    }

    private final String provider;
    private final Kind kind;

    public ProviderException(String provider, Kind kind, String message, Throwable cause) {
        super(provider + ": " + message, cause);
        this.provider = provider;
        this.kind = kind;
    }

    public ProviderException(String provider, Kind kind, String message) {
        this(provider, kind, message, null);
    }

    public String provider() {
        return provider;
    }

    public Kind kind() {
        return kind;
    }
}
