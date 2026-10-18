package dev.cutpay.bnpl.model;

import java.util.Set;

/** What is known about a provider before asking it anything: where and for how much it works. */
public record ProviderInfo(String code, String name, long minAmount, long maxAmount, Set<String> currencies,
                           Set<String> countries) {
}
