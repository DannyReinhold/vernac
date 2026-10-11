// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.util.*;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.EventId;
import org.vernac.runtime.events.*;

/** One claim and one processing transaction per call. External effects are at-least-once. */
@NullMarked
public final class JdbcEventWorker {
    private static final String TABLE=JdbcEventStore.DELIVERY_TABLE;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Supplier<List<EventHandler>> handlers;
    private final EventDeliveryOptions options;

    public JdbcEventWorker(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
                           Supplier<List<EventHandler>> handlers, EventDeliveryOptions options) {
        this.jdbc=Objects.requireNonNull(jdbc); this.handlers=Objects.requireNonNull(handlers);
        this.options=Objects.requireNonNull(options);
        transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Returns false if no due job was available. Failures of delivery are persisted, not rethrown. */
    public boolean runOnce() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run the event worker outside an application transaction");
        var claim=transaction.execute(status->claim());
        if(claim==null) return false;
        try {
            var all=List.copyOf(handlers.get());
            var listeners=all.stream().filter(h->!(h instanceof EventStorage) && h.deferred()
                    && h.listenerId().equals(claim.listener())).toList();
            var stores=all.stream().filter(EventStorage.class::isInstance).map(EventStorage.class::cast)
                    .filter(h->h.eventType().equals(claim.eventType())).toList();
            if(listeners.size()!=1 || stores.size()!=1 || !listeners.getFirst().eventClass().equals(stores.getFirst().eventClass())) {
                transaction.executeWithoutResult(s->block(claim,"Missing, ambiguous or incompatible listener/event storage"));
                return true;
            }
            transaction.executeWithoutResult(status->{
                try(var context=EventContext.open(()->all)) {
                    var event=stores.getFirst().load(EventId.of(claim.eventId()));
                    listeners.getFirst().handle(event);
                    int updated=jdbc.update("UPDATE "+TABLE+" SET status='SUCCEEDED', \"completedAt\"=clock_timestamp(), "
                            +"\"claimToken\"=NULL, \"leaseUntil\"=NULL, \"lastError\"=NULL "
                            +"WHERE id=:id AND status='PROCESSING' AND \"claimToken\"=:token AND \"leaseUntil\">clock_timestamp()",
                            key(claim));
                    if(updated!=1) throw new LostClaimException();
                }
            });
        } catch(RuntimeException failure) {
            // Processing has rolled back before recording failure in a separate transaction.
            transaction.executeWithoutResult(status->fail(claim,failure));
        }
        return true;
    }

    private @Nullable Claim claim() {
        // A crash on the final attempt must not leave PROCESSING jobs stuck forever.
        jdbc.update("WITH exhausted AS (SELECT id FROM "+TABLE
                +" WHERE attempts>=:max AND (status='PENDING' OR status='PROCESSING' AND \"leaseUntil\"<=clock_timestamp())"
                +" FOR UPDATE SKIP LOCKED) UPDATE "+TABLE+" d SET status='FAILED', \"completedAt\"=clock_timestamp(), \"claimToken\"=NULL, "
                +"\"leaseUntil\"=NULL, \"lastError\"='Attempt limit reached; final lease expired or limit reduced' "
                +"FROM exhausted e WHERE d.id=e.id",Map.of("max",options.maxAttempts()));
        var token=UUID.randomUUID();
        var rows=jdbc.query("WITH candidate AS (SELECT id FROM "+TABLE
                +" WHERE (status='PENDING' AND \"nextAttemptAt\"<=clock_timestamp() OR status='PROCESSING' AND \"leaseUntil\"<=clock_timestamp())"
                +" AND attempts<:max ORDER BY \"createdAt\", id LIMIT 1 FOR UPDATE SKIP LOCKED) "
                +"UPDATE "+TABLE+" d SET status='PROCESSING', attempts=d.attempts+1, \"lastAttemptAt\"=clock_timestamp(), "
                +"\"claimToken\"=:token, \"leaseUntil\"=clock_timestamp()+(:lease * interval '1 millisecond') "
                +"FROM candidate c WHERE d.id=c.id RETURNING d.id,d.\"eventType\",d.\"eventId\",d.\"listenerId\",d.attempts",
                Map.of("max",options.maxAttempts(),"token",token,"lease",options.lease().toMillis()),
                (rs,n)->new Claim(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),token,rs.getInt(5)));
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void block(Claim claim,String reason) {
        var values=key(claim);values.put("error",reason);
        jdbc.update("UPDATE "+TABLE+" SET status='BLOCKED', attempts=attempts-1, \"claimToken\"=NULL, \"leaseUntil\"=NULL, \"lastError\"=:error "
                +"WHERE id=:id AND status='PROCESSING' AND \"claimToken\"=:token",values);
    }
    private void fail(Claim claim,RuntimeException error) {
        var values=key(claim);values.put("max",options.maxAttempts());
        values.put("delay",options.backoff(claim.attempt()).toMillis());
        // Avoid storing arbitrary exception messages, which can contain credentials or payloads.
        values.put("error",error.getClass().getName());
        jdbc.update("UPDATE "+TABLE+" SET status=CASE WHEN attempts>=:max THEN 'FAILED' ELSE 'PENDING' END, "
                +"\"completedAt\"=CASE WHEN attempts>=:max THEN clock_timestamp() ELSE NULL END, "
                +"\"nextAttemptAt\"=clock_timestamp()+(:delay * interval '1 millisecond'), \"lastError\"=:error, "
                +"\"claimToken\"=NULL, \"leaseUntil\"=NULL "
                +"WHERE id=:id AND status='PROCESSING' AND \"claimToken\"=:token",values);
    }
    private static Map<String,Object> key(Claim claim) {
        Map<String,Object> values=new HashMap<>(); values.put("id",claim.id());values.put("token",claim.token());return values;
    }
    private record Claim(UUID id,String eventType,UUID eventId,String listener,UUID token,int attempt) {}
    private static final class LostClaimException extends IllegalStateException {
        LostClaimException(){super("Event delivery claim expired or was replaced");}
    }
}
