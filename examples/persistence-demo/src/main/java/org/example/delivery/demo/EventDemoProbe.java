package org.example.delivery.demo;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.delivery.domain.DemoNotice;
import org.vernac.runtime.EventId;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Demo instrumentation only: deliberately simulates a transient external failure. */
public final class EventDemoProbe {
    private static final Logger log=LoggerFactory.getLogger(EventDemoProbe.class);
    private static final ThreadLocal<DemoNotice> observed=new ThreadLocal<>();
    private static final ConcurrentHashMap<EventId,AtomicInteger> attempts=new ConcurrentHashMap<>();
    private EventDemoProbe() {}
    public static void observe(DemoNotice event) {
        requireTransaction(); observed.set(event);
        log.info("Synchronous listener observed {} before publisher commit",event.eventId().asString());
    }
    public static DemoNotice takeObserved() {
        DemoNotice event=observed.get();observed.remove();
        if(event==null) throw new IllegalStateException("Synchronous listener did not run");
        return event;
    }
    public static void deliver(DemoNotice event) {
        requireTransaction();
        log.info("Deferred listener processed {} in its worker transaction",event.eventId().asString());
    }
    public static void deliverWithRetry(DemoNotice event) {
        requireTransaction();
        int attempt=attempts.computeIfAbsent(event.eventId(),id->new AtomicInteger()).incrementAndGet();
        if(attempt==1) {
            log.info("Demo: deliberately failing the first retry-listener attempt for {}",event.eventId().asString());
            throw new IllegalStateException("Simulated transient demo failure");
        }
        log.info("Retry listener processed {} on attempt {}",event.eventId().asString(),attempt);
    }
    static void forget(EventId id) { attempts.remove(id); }
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Listener must run in a transaction");
    }
}
