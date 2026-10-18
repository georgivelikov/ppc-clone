package dev.cutpay.bnpl.routing;

/** How a provider has been doing: the share of applications it approves and how long it takes to decide (median). */
public record Stats(double approvalRate, long p50DecisionMs) {
}
