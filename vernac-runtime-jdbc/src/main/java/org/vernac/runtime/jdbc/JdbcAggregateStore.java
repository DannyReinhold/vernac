// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.*;
import static org.vernac.runtime.jdbc.JdbcMapping.*;

/** Transaction-scoped complete aggregate persistence using explicit generated bindings. */
@NullMarked
public final class JdbcAggregateStore<A extends AggregateRoot<?>> {
    private final NamedParameterJdbcTemplate jdbc;
    private final Class<A> type;
    private final Domain root;
    private final Map<String,Domain> domains=new LinkedHashMap<>();
    private final Map<String,Table> tables=new LinkedHashMap<>();
    private final List<Table> order;

    public JdbcAggregateStore(NamedParameterJdbcTemplate jdbc,Class<A> type,Domain root,List<Domain> entities,List<Table> tables) {
        this.jdbc=Objects.requireNonNull(jdbc); this.type=type; this.root=root;
        domains.put(root.table(),root); entities.forEach(d->domains.put(d.table(),d));
        tables.forEach(t->this.tables.put(t.name(),t));
        order=tables.stream().sorted(Comparator.comparingInt(Table::depth).thenComparing(Table::name)).toList();
    }
    public A byId(UUID id) {
        requireTransaction(); Objects.requireNonNull(id);
        var rows=readRows(id);
        var roots=rows.get(root.table());
        if(roots.isEmpty()) throw new AggregateNotFoundException(type,id);
        long version=((Number)roots.getFirst().get("@version")).longValue();
        var current=jdbc.query("SELECT "+q("@version")+" FROM "+root.table()+" WHERE "+q("id")+"=:id",Map.of("id",id),(rs,n)->rs.getLong(1));
        if(current.size()!=1 || current.getFirst()!=version) throw conflict(id);
        return type.cast(new ReadGraph(rows).entity(root.table(),id));
    }
    /** Loads roots selected in one statement, then batches each relation and checks root versions. */
    public List<A> find(String where, String ordering, SqlParameterSource parameters, boolean singleton, String queryName) {
        requireTransaction();
        Table rootTable=tables.get(root.table());
        String sql="SELECT "+String.join(", ",rootTable.columns().stream().map(c->q(c.name())).toList())
                +" FROM "+root.table()+(where.isEmpty()?"":" WHERE "+where)
                +(ordering.isEmpty()?"":" ORDER BY "+ordering)+(singleton?" LIMIT 2":"");
        var roots=jdbc.query(sql,parameters,(rs,n)->readRow(rootTable,rs));
        if(singleton && roots.size()>1) throw new NonUniqueQueryResultException(queryName);
        if(roots.isEmpty()) return List.of();
        var ids=roots.stream().map(r->(UUID)r.get("id")).toList();
        Map<UUID,Map<String,List<Map<String,@Nullable Object>>>> graphs=new LinkedHashMap<>();
        for(var row:roots) {
            var graph=new LinkedHashMap<String,List<Map<String,@Nullable Object>>>();
            for(var table:order) graph.put(table.name(),new ArrayList<>());
            graph.get(root.table()).add(row);
            graphs.put((UUID)row.get("id"),graph);
        }
        // Bound IN lists in chunks; no per-root or per-entity query loop.
        for(int offset=0;offset<ids.size();offset+=1000) {
            var batch=ids.subList(offset,Math.min(offset+1000,ids.size()));
            for(Table table:order) if(!table.name().equals(root.table())) {
                String owner=table.columns().stream().anyMatch(c->c.name().equals("@aggregateId"))?"@aggregateId":"@ownerId";
                var rows=jdbc.query("SELECT "+String.join(", ",table.columns().stream().map(c->q(c.name())).toList())
                        +" FROM "+table.name()+" WHERE "+q(owner)+" IN (:ids)",Map.of("ids",batch),(rs,n)->readRow(table,rs));
                for(var row:rows) graphs.get((UUID)row.get(owner)).get(table.name()).add(row);
            }
            var versions=jdbc.query("SELECT "+q("id")+", "+q("@version")+" FROM "+root.table()+" WHERE "+q("id")+" IN (:ids)",
                    Map.of("ids",batch),(rs,n)->Map.entry(rs.getObject(1,UUID.class),rs.getLong(2)));
            Map<UUID,Long> current=new HashMap<>(); versions.forEach(e->current.put(e.getKey(),e.getValue()));
            for(UUID id:batch) {
                long expected=((Number)graphs.get(id).get(root.table()).getFirst().get("@version")).longValue();
                if(!Objects.equals(current.get(id),expected)) throw conflict(id);
            }
        }
        List<A> result=new ArrayList<>();
        for(UUID id:ids) result.add(type.cast(new ReadGraph(graphs.get(id)).entity(root.table(),id)));
        return List.copyOf(result);
    }
    public A save(A aggregate) {
        requireTransaction(); Objects.requireNonNull(aggregate);
        UUID id=root.id().apply(aggregate);
        long version=aggregate.persistenceState().version();
        if(version==Long.MAX_VALUE) throw new IllegalStateException("Persistence version exhausted");
        WriteGraph graph=new WriteGraph(id);
        graph.entity(root.table(),aggregate); // Detect conflicting instances before the first SQL write.
        Map<String,@Nullable Object> row=graph.rows.get(root.table()).getFirst();
        row.put("@version",version+1);
        Table rootTable=tables.get(root.table());
        if(version==0) insert(rootTable,row);
        else if(update(rootTable,row," AND "+q("@version")+"=:expectedVersion",version)!=1) throw conflict(id);
        // The root write holds the optimistic-lock row lock until transaction completion.
        var stored=version==0 ? new HashMap<String,List<Map<String,@Nullable Object>>>() : readRows(id);
        for(Table t:order.reversed()) {
            if(t.name().equals(root.table())) continue;
            var desired=index(t,graph.rows.getOrDefault(t.name(),List.of()));
            for(var old:stored.getOrDefault(t.name(),List.of())) {
                var next=desired.get(key(t,old));
                if(next==null || replacedValueRow(old,next)) deleteRow(t,old);
            }
        }
        for(Table t:order) {
            if(t.name().equals(root.table())) continue;
            var existing=index(t,stored.getOrDefault(t.name(),List.of()));
            for(var next:graph.rows.getOrDefault(t.name(),List.of())) {
                var old=existing.get(key(t,next));
                if(old==null || replacedValueRow(old,next)) insert(t,next);
                else if(!old.equals(next)) update(t,next,"",0);
            }
        }
        aggregate.persistenceState().version(version+1);
        return aggregate;
    }
    public void delete(A aggregate) {
        requireTransaction(); UUID id=root.id().apply(aggregate);
        long version=aggregate.persistenceState().version();
        if(version==0) throw conflict(id);
        int locked=jdbc.update("UPDATE "+root.table()+" SET "+q("@version")+"="+q("@version")
                +" WHERE "+q("id")+"=:id AND "+q("@version")+"=:version",Map.of("id",id,"version",version));
        if(locked!=1) throw conflict(id);
        var stored=readRows(id);
        for(Table table:order.reversed()) for(var row:stored.get(table.name())) deleteRow(table,row);
    }
    private Map<String,List<Map<String,@Nullable Object>>> readRows(UUID id) {
        Map<String,List<Map<String,@Nullable Object>>> rows=new LinkedHashMap<>();
        for(Table table:order) rows.put(table.name(),jdbc.query("SELECT "+String.join(", ",table.columns().stream().map(c->q(c.name())).toList())
                +" FROM "+table.name()+" WHERE "+table.predicate(),Map.of("aggregateId",id),(rs,n)->readRow(table,rs)));
        return rows;
    }
    private static Map<String,@Nullable Object> readRow(Table table,ResultSet rs) throws SQLException {
        Map<String,@Nullable Object> row=new LinkedHashMap<>();
        for(Column c:table.columns()) {
            Object value=switch(c.sqlType()) {
                case "DATE" -> rs.getObject(c.name(),LocalDate.class);
                case "TIME(6)" -> rs.getObject(c.name(),LocalTime.class);
                case "TIMESTAMP(6)" -> rs.getObject(c.name(),LocalDateTime.class);
                case "TIMESTAMPTZ(6)" -> rs.getObject(c.name(),OffsetDateTime.class);
                default -> rs.getObject(c.name());
            };
            row.put(c.name(),value);
        }
        return row;
    }
    private static boolean replacedValueRow(Map<String,@Nullable Object> old,Map<String,@Nullable Object> next) {
        return old.containsKey("@rowId") && !Objects.equals(old.get("@rowId"),next.get("@rowId"));
    }
    private void insert(Table t,Map<String,@Nullable Object> row) {
        jdbc.update("INSERT INTO "+t.name()+" ("+String.join(", ",t.columns().stream().map(c->q(c.name())).toList())+") VALUES ("
                +String.join(", ",java.util.stream.IntStream.range(0,t.columns().size()).mapToObj(i->":p"+i).toList())+")",params(t,row));
    }
    private int update(Table t,Map<String,@Nullable Object> row,String suffix,long version) {
        List<String> assignments=new ArrayList<>();
        for(int i=0;i<t.columns().size();i++) if(!t.key().contains(t.columns().get(i).name())) assignments.add(q(t.columns().get(i).name())+"=:p"+i);
        if(assignments.isEmpty()) return 1;
        return jdbc.update("UPDATE "+t.name()+" SET "+String.join(", ",assignments)+" WHERE "+where(t)+suffix,
                params(t,row).addValue("expectedVersion",version));
    }
    private void deleteRow(Table t,Map<String,@Nullable Object> row) { jdbc.update("DELETE FROM "+t.name()+" WHERE "+where(t),params(t,row)); }
    private static String where(Table t) {
        List<String> clauses=new ArrayList<>();
        for(int i=0;i<t.columns().size();i++) if(t.key().contains(t.columns().get(i).name())) clauses.add(q(t.columns().get(i).name())+"=:p"+i);
        return String.join(" AND ",clauses);
    }
    private static MapSqlParameterSource params(Table t,Map<String,@Nullable Object> row) {
        var p=new MapSqlParameterSource();
        for(int i=0;i<t.columns().size();i++) {
            var c=t.columns().get(i); Object value=row.get(c.name());
            // Non-null JDBC 4.2 values retain their native Java time type. NULL needs a declared SQL type.
            if(value==null) p.addValue("p"+i,null,jdbcType(c.sqlType())); else p.addValue("p"+i,value);
        }
        return p;
    }
    private static int jdbcType(String type) {
        return switch(type) {
            case "UUID" -> Types.OTHER; case "TEXT" -> Types.VARCHAR; case "BOOLEAN" -> Types.BOOLEAN;
            case "SMALLINT" -> Types.SMALLINT; case "INTEGER" -> Types.INTEGER; case "BIGINT" -> Types.BIGINT;
            case "NUMERIC" -> Types.NUMERIC; case "REAL" -> Types.REAL; case "DOUBLE PRECISION" -> Types.DOUBLE;
            case "DATE" -> Types.DATE; case "TIME(6)" -> Types.TIME; case "TIMESTAMP(6)" -> Types.TIMESTAMP;
            case "TIMESTAMPTZ(6)" -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> throw new IllegalArgumentException("Unsupported JDBC column type "+type);
        };
    }
    private static Map<List<Object>,Map<String,@Nullable Object>> index(Table table,List<Map<String,@Nullable Object>> rows) {
        Map<List<Object>,Map<String,@Nullable Object>> result=new HashMap<>();
        for(var row:rows) if(result.put(key(table,row),row)!=null) throw malformed("Duplicate row key: "+table.name());
        return result;
    }
    private static List<Object> key(Table t,Map<String,@Nullable Object> row) { return t.key().stream().map(row::get).toList(); }
    private static String q(String identifier) { return "\""+identifier.replace("\"","\"\"")+"\""; }
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalTransactionStateException("Vernac repositories require an active usecase transaction");
    }
    private OptimisticLockingFailureException conflict(UUID id) { return new OptimisticLockingFailureException("Aggregate changed or was removed: "+type.getName()+" / "+id); }
    private record Identity(String table,UUID id) { }

    private final class WriteGraph implements Writer {
        final UUID aggregateId;
        final Map<String,List<Map<String,@Nullable Object>>> rows=new LinkedHashMap<>();
        final Map<Identity,Object> instances=new HashMap<>();
        final Set<Identity> active=new HashSet<>();
        WriteGraph(UUID aggregateId) { this.aggregateId=aggregateId; }
        public Map<String,@Nullable Object> row(String table) {
            Map<String,@Nullable Object> row=new LinkedHashMap<>();
            for(var column:tables.get(table).columns()) row.put(column.name(),null);
            if(row.containsKey("@aggregateId")) row.put("@aggregateId",aggregateId);
            if(row.containsKey("@rowId")) row.put("@rowId",UUID.randomUUID());
            rows.computeIfAbsent(table,k->new ArrayList<>()).add(row);
            return row;
        }
        public UUID entity(String table,Object value) {
            Domain domain=domains.get(table); UUID id=domain.id().apply(value); Identity key=new Identity(table,id);
            if(active.contains(key)) throw new IllegalArgumentException("Cyclic entity graph: "+key);
            Object previous=instances.putIfAbsent(key,value);
            if(previous!=null) {
                if(previous!=value) throw new IllegalArgumentException("Different entity instances share one identity: "+key);
                return id;
            }
            active.add(key);
            Map<String,@Nullable Object> row=row(table); row.put("id",id);
            for(Field f:domain.fields()) f.value().write(f.getter().apply(value),row,this,true);
            active.remove(key);
            return id;
        }
    }
    private final class ReadGraph implements Reader {
        final Map<String,List<Map<String,@Nullable Object>>> rows;
        final Map<Identity,Object> instances=new HashMap<>();
        final Set<Identity> active=new HashSet<>();
        final Map<String,Map<UUID,Map<String,@Nullable Object>>> states=new HashMap<>();
        ReadGraph(Map<String,List<Map<String,@Nullable Object>>> rows) {
            this.rows=rows;
            for(String table:domains.keySet()) {
                Map<UUID,Map<String,@Nullable Object>> indexed=new HashMap<>();
                for(var row:rows.get(table)) indexed.put((UUID)row.get("id"),row);
                states.put(table,indexed);
            }
        }
        public List<Map<String,@Nullable Object>> rows(String table) { return rows.get(table); }
        public Object entity(String table,UUID id) {
            Identity key=new Identity(table,id);
            if(instances.containsKey(key)) return instances.get(key);
            if(!active.add(key)) throw malformed("Cyclic stored entity graph: "+key);
            var row=states.get(table).get(id);
            if(row==null) throw malformed("Missing entity state: "+key);
            var d=domains.get(table);
            Object value=d.restore().apply(id,d.fields().stream().map(f->f.value().read(row,this)).toArray());
            active.remove(key); instances.put(key,value); return value;
        }
    }
}
