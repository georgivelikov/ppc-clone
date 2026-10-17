package dev.cutpay.gateway.requestlog;

import java.time.Instant;

/**
 * One API call as the request log sees it. There are deliberately no bodies, headers or query strings:
 * those can hold card data, tokens and personal data, and the log is for debugging integrations, not auditing.
 *
 * @param actorType {@code api_key} when the caller used an API key, {@code user} for a dashboard session
 */
public record RequestLogEntry(String merchantId, boolean livemode, String method, String path, int status,
                              long latencyMs, String actor, String actorType, String idempotencyKey,
                              String requestId, Instant at) {
}
