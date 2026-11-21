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
import org.vernac.runtime.tenancy.*;

/** Independent claims, exact-origin processing, then unscoped failure bookkeeping. External effects are at-least-once. */
@NullMarked
public final class JdbcEventWorker {
    private static final String TABLE=JdbcEventStore.DELIVERY_TABLE;
    private static final String OWNED="id=:id AND status='PROCESSING' AND \"claimToken\"=:token"
            +" AND \"tenantId\" IS NOT DISTINCT FROM CAST(:tenant AS uuid)";
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

    /** Returns false if no due job was available. Delivery failures are recorded after rollback. */
    public boolean runOnce() {
        requireUnscopedEntry();
        var claim=inGlobalTransaction(this::claim);
        if(claim==null) return false;
        try {
            var all=List.copyOf(handlers.get());
            var selected=select(claim,all);
            if(selected==null) return true;
            process(claim,selected,all);
        } catch(RuntimeException failure) {
            inGlobalTransaction(()->fail(claim,failure));
        }
        return true;
    }

    private static void requireUnscopedEntry() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run the event worker outside an application transaction");
        if(TenantContext.currentTenant().isPresent() || TenantContext.transactionTenant().isPresent())
            throw new TenancyException(TenancyException.Code.TENANT_CONTEXT_MISMATCH);
    }

    private @Nullable Selected select(Claim claim,List<EventHandler> all) {
        var listeners=all.stream().filter(h->!(h instanceof EventStorage) && h.deferred()
                && h.listenerId().equals(claim.listener())).toList();
        var stores=all.stream().filter(EventStorage.class::isInstance).map(EventStorage.class::cast)
                .filter(h->h.eventType().equals(claim.eventType())).toList();
        if(listeners.size()!=1 || stores.size()!=1 || !listeners.getFirst().eventClass().equals(stores.getFirst().eventClass())) {
            inGlobalTransaction(()->block(claim,"Missing, ambiguous or incompatible listener/event storage"));
            return null;
        }
        if(claim.tenant()!=null && stores.getFirst().tenantDeliveryVersion()!=1) {
            inGlobalTransaction(()->block(claim,"Tenant event storage requires producer module/application rebuild"));
            return null;
        }
        return new Selected(stores.getFirst(),listeners.getFirst());
    }

    private void process(Claim claim,Selected selected,List<EventHandler> all) {
        if(claim.tenant()==null) {
            transaction.executeWithoutResult(status->processInside(claim,selected,all));
        } else {
            // Persisted routing is trusted infrastructure input, never an HTTP/header tenant selection.
            try(var scope=TenantContext.openVerified(TenantId.of(claim.tenant()))) {
                transaction.executeWithoutResult(status->processInside(claim,selected,all));
            }
        }
    }

    private void processInside(Claim claim,Selected selected,List<EventHandler> all) {
        var origin=EventOrigin.capture();
        if(origin.isGlobal()) JdbcEventOrigin.requireGlobal(jdbc);
        try(var context=EventContext.open(()->all)) {
            var event=selected.store().load(EventId.of(claim.eventId()),origin);
            selected.listener().handle(event);
            selected.store().verifyOrigin(origin);
            if(origin.isGlobal()) JdbcEventOrigin.requireGlobal(jdbc);
            int updated=jdbc.update("UPDATE "+TABLE+" SET status='SUCCEEDED', \"completedAt\"=clock_timestamp(), "
                    +"\"claimToken\"=NULL, \"leaseUntil\"=NULL, \"lastError\"=NULL WHERE "+OWNED
                    +" AND \"leaseUntil\">clock_timestamp()",key(claim));
            if(updated!=1) throw new LostClaimException();
        }
    }

    private <T> @Nullable T inGlobalTransaction(Supplier<T> action) {
        try {return transaction.execute(status->{
            JdbcEventOrigin.requireGlobal(jdbc);
            JdbcEventDeliveryLayout.verify(jdbc);
            T result=action.get();
            JdbcEventOrigin.requireGlobal(jdbc);
            return result;
        });} catch(org.springframework.dao.DataAccessException failed) {
            throw new org.vernac.runtime.VernacTechnicalException("Event delivery control-plane operation failed. Check database access and migration state.");
        }
    }
    private void inGlobalTransaction(Runnable action) {
        inGlobalTransaction(()->{action.run();return null;});
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
                +"FROM candidate c WHERE d.id=c.id RETURNING d.id,d.\"eventType\",d.\"eventId\",d.\"listenerId\",d.attempts,d.\"tenantId\"",
                Map.of("max",options.maxAttempts(),"token",token,"lease",options.lease().toMillis()),
                (rs,n)->new Claim(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),token,rs.getInt(5),rs.getObject(6,UUID.class)));
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void block(Claim claim,String reason) {
        var values=key(claim);values.put("error",reason);
        jdbc.update("UPDATE "+TABLE+" SET status='BLOCKED', attempts=attempts-1, \"claimToken\"=NULL, \"leaseUntil\"=NULL, \"lastError\"=:error WHERE "+OWNED,values);
    }
    private void fail(Claim claim,RuntimeException error) {
        var values=key(claim);values.put("max",options.maxAttempts());
        values.put("delay",options.backoff(claim.attempt()).toMillis());
        // Never retain arbitrary exception messages: they can contain credentials or payloads.
        values.put("error",error.getClass().getName());
        jdbc.update("UPDATE "+TABLE+" SET status=CASE WHEN attempts>=:max THEN 'FAILED' ELSE 'PENDING' END, "
                +"\"completedAt\"=CASE WHEN attempts>=:max THEN clock_timestamp() ELSE NULL END, "
                +"\"nextAttemptAt\"=clock_timestamp()+(:delay * interval '1 millisecond'), \"lastError\"=:error, "
                +"\"claimToken\"=NULL, \"leaseUntil\"=NULL WHERE "+OWNED,values);
    }
    private static Map<String,@Nullable Object> key(Claim claim) {
        Map<String,@Nullable Object> values=new HashMap<>();values.put("id",claim.id());values.put("token",claim.token());values.put("tenant",claim.tenant());return values;
    }
    private record Selected(EventStorage store,EventHandler listener) {}
    private record Claim(UUID id,String eventType,UUID eventId,String listener,UUID token,int attempt,@Nullable UUID tenant) {
        @Override public String toString(){return "EventDeliveryClaim[redacted]";}
    }
    private static final class LostClaimException extends IllegalStateException {
        LostClaimException(){super("Event delivery claim expired or was replaced");}
    }
}
