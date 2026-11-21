// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.jspecify.annotations.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.*;
import org.vernac.runtime.events.EventOrigin;
import org.vernac.runtime.jdbc.tenancy.JdbcTenantCoordinator;
import static org.vernac.runtime.jdbc.JdbcMapping.*;
import static org.vernac.runtime.jdbc.JdbcRows.*;

/** Append-only event graphs and per-listener jobs, in the caller's transaction. No worker. */
@NullMarked
public final class JdbcEventStore<E extends DomainEvent> {
    public static final String DELIVERY_TABLE = "\"vernac\".\"@delivery\"";
    private final NamedParameterJdbcTemplate jdbc;
    private final Class<E> type;
    private final String eventType;
    private final Domain root;
    private final Map<String,Table> tables = new LinkedHashMap<>();
    private final List<Table> order;
    private final boolean originSchema;
    private final @Nullable JdbcTenantCoordinator tenantBinding;

    public JdbcEventStore(NamedParameterJdbcTemplate jdbc, Class<E> type, String eventType, Domain root, List<Table> tables) {
        this(jdbc,type,eventType,root,tables,false);
    }

    /**
     * Explicit generated layout capability; never inferred from an older adapter.
     * Tenant operations additionally require verified nonowner RLS execution.
     */
    public static <E extends DomainEvent> JdbcEventStore<E> withOriginSchema(NamedParameterJdbcTemplate jdbc,
            Class<E> type, String eventType, Domain root, List<Table> tables) {
        return new JdbcEventStore<>(jdbc,type,eventType,root,tables,true);
    }

    private JdbcEventStore(NamedParameterJdbcTemplate jdbc, Class<E> type, String eventType, Domain root, List<Table> tables, boolean originSchema) {
        this.originSchema=originSchema;
        this.jdbc=Objects.requireNonNull(jdbc); this.type=type; this.eventType=eventType; this.root=root;
        tenantBinding=originSchema?JdbcTenantCoordinator.forEventPayloads(
                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource()),tables.stream().map(Table::name).toList()):null;
        tables.forEach(t -> this.tables.put(t.name(),t));
        order=tables.stream().sorted(Comparator.comparingInt(Table::depth).thenComparing(Table::name)).toList();
    }

    public void persist(E event, List<String> listenerIds) {
        persist(event,listenerIds,EventOrigin.capture());
    }

    public void persist(E event, List<String> listenerIds, EventOrigin origin) {
        try {persistInside(event,listenerIds,origin);}
        catch(org.springframework.dao.DataAccessException failed) {
            if(!origin.isGlobal()) throw new org.vernac.runtime.tenancy.TenancyException(
                    org.vernac.runtime.tenancy.TenancyException.Code.TENANT_STORAGE_FAILURE);
            throw failed;
        }
    }
    private void persistInside(E event, List<String> listenerIds, EventOrigin origin) {
        verifyOrigin(origin); Objects.requireNonNull(event);
        if(!listenerIds.isEmpty()) JdbcEventDeliveryLayout.verify(jdbc);
        if(new HashSet<>(listenerIds).size()!=listenerIds.size()) throw new IllegalArgumentException("Duplicate delivery recipient");
        var graph=new WriteGraph(origin);
        var row=graph.row(root.table());
        row.put("@eventId",event.eventId().value());
        for(var field:root.fields()) field.value().write(field.getter().apply(event),row,graph,true);
        int inserted=insert(tables.get(root.table()),row,true);
        if(inserted==0) {
            E existing=load(event.eventId(),origin);
            for(var field:root.fields()) if(!Objects.equals(field.getter().apply(existing),field.getter().apply(event)))
                throw new VernacTechnicalException("Conflicting payload for stored event "+eventType+" / "+event.eventId());
            // Subscriber set is fixed by the first publication; never replay to newly added listeners.
            return;
        }
        for(var table:order) if(!table.name().equals(root.table()))
            for(var child:graph.rows.getOrDefault(table.name(),List.of())) insert(table,child,false);
        var now=Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        for(String listener:listenerIds) {
            jdbc.update("INSERT INTO "+DELIVERY_TABLE+" (\"id\", \"eventType\", \"eventId\", \"listenerId\", \"status\", \"attempts\", \"createdAt\", \"nextAttemptAt\", \"tenantId\") "
                    +"VALUES (:id, :type, :event, :listener, 'PENDING', 0, :now, :now, :tenant)",
                    new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("type",eventType)
                            .addValue("event",event.eventId().value()).addValue("listener",listener).addValue("now",now)
                            .addValue("tenant",origin.tenant().map(t->t.value()).orElse(null),java.sql.Types.OTHER));
        }
    }

    public E load(EventId id) {
        return load(id,EventOrigin.capture());
    }

    public E load(EventId id, EventOrigin origin) {
        try {return loadInside(id,origin);}
        catch(org.springframework.dao.DataAccessException failed) {
            if(!origin.isGlobal()) throw new org.vernac.runtime.tenancy.TenancyException(
                    org.vernac.runtime.tenancy.TenancyException.Code.TENANT_STORAGE_FAILURE);
            throw failed;
        }
    }
    private E loadInside(EventId id, EventOrigin origin) {
        verifyOrigin(origin); Objects.requireNonNull(id);
        var graph=new LinkedHashMap<String,List<Map<String,@Nullable Object>>>();
        for(var table:order) graph.put(table.name(),jdbc.query("SELECT "+String.join(", ",table.columns().stream().map(c->q(c.name())).toList())
                +" FROM "+table.name()+" WHERE "+loadPredicate(table),loadParameters(id,origin),(rs,n)->readRow(table,rs)));
        var roots=graph.get(root.table());
        if(roots.size()!=1) throw new VernacTechnicalException("Stored event not found: "+eventType+" / "+id);
        Reader reader=new Reader() {
            public Object entity(String table,UUID entityId) { throw new IllegalStateException("Events cannot contain entities"); }
            public List<Map<String,@Nullable Object>> rows(String table) { return graph.getOrDefault(table,List.of()); }
        };
        Object[] values=root.fields().stream().map(f->f.value().read(roots.getFirst(),reader)).toArray();
        return type.cast(root.restore().apply(id.value(),values));
    }

    private int insert(Table table,Map<String,@Nullable Object> row,boolean ignoreExisting) {
        return jdbc.update("INSERT INTO "+table.name()+" ("+String.join(", ",table.columns().stream().map(c->q(c.name())).toList())+") VALUES ("
                +String.join(", ",java.util.stream.IntStream.range(0,table.columns().size()).mapToObj(i->":p"+i).toList())+")"
                +(ignoreExisting?" ON CONFLICT (\"@eventId\") DO NOTHING":""),params(table,row));
    }
    private String loadPredicate(Table table) {
        return originSchema ? "("+table.predicate()+") AND \"@scopeKey\"=:__vernacEventScope AND \"@tenantId\" IS NOT DISTINCT FROM CAST(:__vernacEventTenant AS uuid)" : table.predicate();
    }
    private MapSqlParameterSource loadParameters(EventId id, EventOrigin origin) {
        return new MapSqlParameterSource("aggregateId",id.value()).addValue("__vernacEventScope",scopeKey(origin))
                .addValue("__vernacEventTenant",origin.tenant().map(t->t.value()).orElse(null),java.sql.Types.OTHER);
    }
    /** Verify again after a listener before committing its business effects and acknowledgement. */
    public void verifyOrigin(EventOrigin origin) {
        Objects.requireNonNull(origin).requireCurrent();
        if(!originSchema) origin.requireGlobal();
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Event persistence requires an active synchronized transaction");
        if(origin.isGlobal()) JdbcEventOrigin.requireGlobal(jdbc);
        else Objects.requireNonNull(tenantBinding).requireBound();
        JdbcEventLayout.verify(jdbc,order,originSchema);
    }
    private static String scopeKey(EventOrigin origin) {
        return origin.tenant().map(t->"TENANT:"+t.asString()).orElse("GLOBAL");
    }
    private final class WriteGraph implements Writer {
        private final EventOrigin origin;
        WriteGraph(EventOrigin origin) {this.origin=origin;}
        final Map<String,List<Map<String,@Nullable Object>>> rows=new LinkedHashMap<>();
        public UUID entity(String table,Object value) { throw new IllegalStateException("Events cannot contain entities"); }
        public Map<String,@Nullable Object> row(String table) {
            Map<String,@Nullable Object> row=new LinkedHashMap<>();
            for(var column:tables.get(table).columns()) row.put(column.name(),null);
            if(originSchema) { row.put("@tenantId",origin.tenant().map(t->t.value()).orElse(null)); row.put("@scopeKey",scopeKey(origin)); }
            if(row.containsKey("@rowId")) row.put("@rowId",UUID.randomUUID());
            rows.computeIfAbsent(table,k->new ArrayList<>()).add(row);
            return row;
        }
    }
}
