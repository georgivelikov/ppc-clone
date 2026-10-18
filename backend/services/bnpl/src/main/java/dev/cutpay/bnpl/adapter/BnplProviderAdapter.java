package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.QuoteRequest;
import java.util.List;

/**
 * The anti-corruption layer for one provider: Cutpay's words go in and out, and the provider's units, field names and
 * ways of answering stay inside the implementation.
 */
public interface BnplProviderAdapter {

    ProviderInfo info();

    /** The ways the provider offers to pay {@code request.amount()}. Throws {@link ProviderException} if it cannot say. */
    List<Offer> quote(QuoteRequest request);
}
