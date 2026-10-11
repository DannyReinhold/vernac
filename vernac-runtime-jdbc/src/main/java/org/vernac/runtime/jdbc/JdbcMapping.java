// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.*;
import org.springframework.dao.DataRetrievalFailureException;

/** Explicit generated bindings. No reflection, field access or runtime mapping conventions. */
@NullMarked
public final class JdbcMapping {
    private JdbcMapping() { }
    public record Column(String name, String sqlType) { }
    public record Table(String name, String predicate, int depth, List<Column> columns, List<String> key) {
        public Table { columns=List.copyOf(columns); key=List.copyOf(key); }
    }
    public record Field(Function<Object,@Nullable Object> getter, Value value) { }
    public record Domain(String table, Function<Object,UUID> id, List<Field> fields,
                         BiFunction<UUID,@Nullable Object[],Object> restore) {
        public Domain { fields=List.copyOf(fields); }
    }
    public interface Writer {
        UUID entity(String table, Object value);
        Map<String,@Nullable Object> row(String table);
    }
    public interface Reader {
        Object entity(String table, UUID id);
        List<Map<String,@Nullable Object>> rows(String table);
    }
    public interface Value {
        void write(@Nullable Object value, Map<String,@Nullable Object> row, Writer writer, boolean ownerPresent);
        @Nullable Object read(Map<String,@Nullable Object> row, Reader reader);
    }
    private static Object[] encodeScalar(String type, Object value, String column) {
        try {
            return ScalarCodec.encode(type, value);
        } catch (org.vernac.runtime.VernacTechnicalException failure) {
            throw new org.vernac.runtime.VernacTechnicalException(
                    "Column " + column + ": " + failure.getMessage(), failure);
        }
    }

    public static Value scalar(String type, boolean optional, String... columns) {
        return new Value() {
            public void write(@Nullable Object value, Map<String,@Nullable Object> row, Writer w, boolean owner) {
                if(value==null && owner && !optional) throw new IllegalArgumentException("Required scalar is null: " + columns[0]);
                Object[] parts=value==null ? new Object[columns.length] : encodeScalar(type,value,columns[0]);
                for(int i=0;i<columns.length;i++) row.put(columns[i],parts[i]);
            }
            public @Nullable Object read(Map<String,@Nullable Object> row, Reader r) {
                Object[] parts=Arrays.stream(columns).map(row::get).toArray();
                long present=Arrays.stream(parts).filter(Objects::nonNull).count();
                if(present==0 && optional) return null;
                if(present!=parts.length) throw malformed("Incomplete scalar " + columns[0]);
                return ScalarCodec.decode(type,parts);
            }
        };
    }
    public static Value converted(Value storage, Function<Object,Object> encode, Function<Object,Object> decode) {
        return new Value() {
            public void write(@Nullable Object value,Map<String,@Nullable Object> row,Writer w,boolean owner) { storage.write(value==null?null:encode.apply(value),row,w,owner); }
            public @Nullable Object read(Map<String,@Nullable Object> row,Reader r) { Object value=storage.read(row,r);return value==null?null:decode.apply(value); }
        };
    }
    public static Value object(boolean optional, @Nullable String witness, @Nullable String marker, List<Field> fields, Function<@Nullable Object[],Object> create) {
        return new Value() {
            public void write(@Nullable Object value,Map<String,@Nullable Object> row,Writer w,boolean owner) {
                if(value==null && owner && !optional) throw new IllegalArgumentException("Required value object is null");
                if(marker!=null) row.put(marker,owner ? value!=null : null);
                for(Field f:fields) f.value.write(value==null?null:f.getter.apply(value),row,w,value!=null);
            }
            public @Nullable Object read(Map<String,@Nullable Object> row,Reader r) {
                if(optional && (marker!=null ? !Boolean.TRUE.equals(row.get(marker)) : row.get(witness)==null)) return null;
                return create.apply(fields.stream().map(f->f.value.read(row,r)).toArray());
            }
        };
    }
    public static Value entity(String table, String column, boolean optional) {
        return new Value() {
            public void write(@Nullable Object value,Map<String,@Nullable Object> row,Writer w,boolean owner) {
                if(value==null && owner && !optional) throw new IllegalArgumentException("Required entity is null");
                row.put(column,value==null?null:w.entity(table,value));
            }
            public @Nullable Object read(Map<String,@Nullable Object> row,Reader r) {
                UUID id=(UUID)row.get(column);
                if(id==null && !optional) throw malformed("Missing entity reference " + column);
                return id==null?null:r.entity(table,id);
            }
        };
    }
    public static Value collection(String table,String ownerColumn,String ownerKey,@Nullable String marker,boolean optional,
                                   boolean ordered,Value element,Function<List<Object>,Object> create) {
        return new Value() {
            public void write(@Nullable Object value,Map<String,@Nullable Object> row,Writer w,boolean owner) {
                if(value==null && owner && !optional) throw new IllegalArgumentException("Required collection is null");
                if(marker!=null) row.put(marker,owner ? value!=null : null);
                if(value==null) return;
                int index=0;
                for(Object item:(Iterable<?>)value) {
                    Objects.requireNonNull(item,"Null collection element");
                    Map<String,@Nullable Object> child=w.row(table);
                    child.put(ownerColumn,row.get(ownerKey));
                    if(ordered) child.put("@position",index++);
                    element.write(item,child,w,true);
                }
            }
            public @Nullable Object read(Map<String,@Nullable Object> row,Reader r) {
                var rows=new ArrayList<>(r.rows(table).stream().filter(item->Objects.equals(item.get(ownerColumn),row.get(ownerKey))).toList());
                if(optional && !Boolean.TRUE.equals(row.get(marker))) {
                    if(!rows.isEmpty()) throw malformed("Rows under an absent collection: " + table);
                    return null;
                }
                if(ordered) rows.sort(Comparator.comparingInt(item->((Number)item.get("@position")).intValue()));
                List<Object> items=new ArrayList<>();
                for(int i=0;i<rows.size();i++) {
                    if(ordered && ((Number)rows.get(i).get("@position")).intValue()!=i) throw malformed("Non-contiguous list positions: " + table);
                    items.add(Objects.requireNonNull(element.read(rows.get(i),r),"Null collection element"));
                }
                if(!ordered && new HashSet<>(items).size()!=items.size()) throw malformed("Duplicate set values: " + table);
                return create.apply(items);
            }
        };
    }
    static DataRetrievalFailureException malformed(String message) { return new DataRetrievalFailureException(message); }
}
