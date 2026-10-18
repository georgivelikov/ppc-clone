package dev.cutpay.bnpl.routing;

/** Where the engine learns how a provider has been doing. */
public interface StatsSource {

    Stats stats(String provider);
}
