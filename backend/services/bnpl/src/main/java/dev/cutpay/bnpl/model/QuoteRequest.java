package dev.cutpay.bnpl.model;

/** What a provider is asked to price. */
public record QuoteRequest(long amount, String currency, String country) {
}
