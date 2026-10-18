package dev.cutpay.bnpl.model;

/**
 * What a provider is asked to decide. {@code reference} is Cutpay id of the application: providers echo it back, which
 * is how a callback is matched to its application.
 */
public record ApplicationRequest(String reference, long amount, String currency, String plan, int instalmentCount,
                                 Applicant applicant) {
}
