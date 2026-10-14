// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.receipts.adapter.inbound.event.fixture;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.vernac.runtime.DomainValidationException;

/** Local walkthrough only: deliberately fail the first delivery attempt after its SQL write. */
public final class DeliveryProbe {
    private DeliveryProbe() { }
    private static final Set<String> ATTEMPTED = ConcurrentHashMap.newKeySet();
    public static void afterWrite(String eventId) {
        if (ATTEMPTED.add(eventId)) {
            LoggerFactory.getLogger(DeliveryProbe.class).info("Deliberate first-attempt failure for {}; notification SQL will roll back", eventId);
            throw new DomainValidationException("Local demo: retry this delivery");
        }
        LoggerFactory.getLogger(DeliveryProbe.class).info("Retry succeeded for {}; notification SQL will commit", eventId);
    }
}
