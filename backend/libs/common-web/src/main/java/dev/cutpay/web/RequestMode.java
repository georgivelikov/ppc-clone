package dev.cutpay.web;

/**
 * Decides whether a request works on live or test data.
 *
 * <p>An API key carries its mode in the token's {@code livemode} claim, and that claim always wins, so a test
 * key cannot read live data by adding {@code ?livemode=true}. Dashboard sessions carry no claim, so they
 * choose with the request parameter (default: test).
 */
public final class RequestMode {

    private RequestMode() {
    }

    public static boolean resolve(Boolean tokenClaim, boolean requested) {
        return tokenClaim != null ? tokenClaim : requested;
    }
}
