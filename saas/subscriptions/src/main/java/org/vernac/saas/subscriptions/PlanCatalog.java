// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.subscriptions;

import java.util.*;
import org.vernac.saas.subscriptions.domain.*;

/** Explicit application-owned offers. No classpath scanning, provider I/O or mutable live terms. */
public final class PlanCatalog {
    public static final int MAX_OFFERS = 128;
    public record OfferId(PlanKey plan, OfferRevision revision) {
        public OfferId { Objects.requireNonNull(plan); Objects.requireNonNull(revision); }
    }
    private final Map<OfferId, OfferTerms> offers;
    private final OfferTerms initial;

    public PlanCatalog(Collection<OfferTerms> offers, OfferId initialFreeOffer) {
        Objects.requireNonNull(offers); Objects.requireNonNull(initialFreeOffer);
        if (offers.isEmpty() || offers.size() > MAX_OFFERS) throw invalid();
        var result = new LinkedHashMap<OfferId, OfferTerms>();
        for (var offer : offers) {
            if (offer == null || result.putIfAbsent(id(offer), offer) != null) throw invalid();
        }
        initial = result.get(initialFreeOffer);
        if (initial == null || initial.price().value() != 0) throw invalid();
        this.offers = Map.copyOf(result);
    }
    public OfferTerms initialFreeOffer() { return initial; }
    public OfferTerms offer(OfferId id) {
        var offer = offers.get(Objects.requireNonNull(id));
        if (offer == null) throw invalid();
        return offer;
    }
    public Set<OfferId> offerIds() { return offers.keySet(); }
    /** Startup/migration check for a retained snapshot. Never rewrites customer terms. */
    public void requireCompatible(OfferTerms retained) {
        var configured = offers.get(id(Objects.requireNonNull(retained)));
        if (configured != null && !configured.equals(retained)) throw invalid();
    }
    public static OfferId id(OfferTerms offer) { return new OfferId(offer.plan(), offer.revision()); }
    private static SubscriptionFailure invalid() { return new SubscriptionFailure(SubscriptionFailure.Code.INVALID_CATALOG); }
}
