// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.modules;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import javax.sql.DataSource;
import org.vernac.runtime.persistence.*;
/** Read-only structural checks. CHECK expression equivalence remains the schema-check goal's job. */
final class JdbcModuleSchemaValidator {
    private JdbcModuleSchemaValidator() { }
    static void validate(DataSource source,SchemaDescription expected,Set<String> owned,String owner) {
        Map<String,SchemaDescription.Table> wanted=new TreeMap<>();expected.tables().forEach(t->wanted.put(SqlIdentifiers.physical(t.namespace())+"/"+SqlIdentifiers.physical(t.name()),t));
        Set<String> tables=new TreeSet<>();Map<String,Map<String,String>> columns=new TreeMap<>();Map<String,Map<String,Constraint>> constraints=new TreeMap<>();
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            try(var rows=statement.executeQuery("SELECT n.nspname,t.relname,t.relkind FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace WHERE t.relkind IN ('r','p')")) {
                while(rows.next()){String key=rows.getString(1)+"/"+rows.getString(2);if(owned.contains(key)){tables.add(key);if(!rows.getString(3).equals("r"))fail(owner,"Unsupported table kind: "+key);}}
            }
            try(var rows=statement.executeQuery("SELECT n.nspname,t.relname,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,COALESCE(pg_get_expr(d.adbin,d.adrelid),''),a.attidentity,a.attgenerated FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_attribute a ON a.attrelid=t.oid LEFT JOIN pg_attrdef d ON d.adrelid=t.oid AND d.adnum=a.attnum WHERE a.attnum>0 AND NOT a.attisdropped AND t.relkind IN ('r','p')")) {
                while(rows.next()){String key=rows.getString(1)+"/"+rows.getString(2);if(owned.contains(key))columns.computeIfAbsent(key,k->new TreeMap<>()).put(rows.getString(3),rows.getString(4)+":"+rows.getBoolean(5)+":"+rows.getString(6)+":"+rows.getString(7)+":"+rows.getString(8));}
            }
            try(var rows=statement.executeQuery("SELECT n.nspname,t.relname,k.conname,k.contype,k.convalidated,k.condeferrable,k.condeferred,ARRAY(SELECT a.attname FROM unnest(k.conkey) WITH ORDINALITY x(num,ord) JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=x.num ORDER BY x.ord),rn.nspname,rt.relname,ARRAY(SELECT a.attname FROM unnest(k.confkey) WITH ORDINALITY x(num,ord) JOIN pg_attribute a ON a.attrelid=k.confrelid AND a.attnum=x.num ORDER BY x.ord) FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace LEFT JOIN pg_class rt ON rt.oid=k.confrelid LEFT JOIN pg_namespace rn ON rn.oid=rt.relnamespace")) {
                while(rows.next()){String key=rows.getString(1)+"/"+rows.getString(2);if(owned.contains(key))constraints.computeIfAbsent(key,k->new TreeMap<>()).put(rows.getString(3),new Constraint(rows.getString(4),rows.getBoolean(5),rows.getBoolean(6),rows.getBoolean(7),strings(rows.getArray(8)),Objects.toString(rows.getString(9),"")+"/"+Objects.toString(rows.getString(10),""),strings(rows.getArray(11))));}
            }
        }catch(SQLException e){throw new IllegalStateException("Could not validate owned PostgreSQL schema for "+owner+" (SQLState "+e.getSQLState()+")",e);}
        if(!tables.equals(wanted.keySet()))fail(owner,"Missing/unexpected owned tables: expected "+wanted.keySet()+", observed "+tables);
        for(var entry:wanted.entrySet()) {
            var table=entry.getValue();Map<String,String> expectedColumns=new TreeMap<>();
            for(var column:table.columns())expectedColumns.put(SqlIdentifiers.physical(column.name()),type(column.sqlType())+":"+!column.nullable()+":::");
            if(!expectedColumns.equals(columns.get(entry.getKey())))fail(owner,"Column type/nullability/default mismatch: "+entry.getKey());
            var actual=constraints.getOrDefault(entry.getKey(),Map.of());Set<String> expectedNames=new TreeSet<>();String pk=SqlIdentifiers.physical("@pk:"+table.name());expectedNames.add(pk);
            var primary=actual.get(pk);if(primary==null||!primary.kind().equals("p")||!primary.validated()||!primary.columns().equals(table.primaryKey().stream().map(SqlIdentifiers::physical).toList()))fail(owner,"Primary key mismatch: "+entry.getKey());
            for(var constraint:table.constraints()) {
                String name=SqlIdentifiers.physical(constraint.name()),clause=constraint.clause();expectedNames.add(name);var observed=actual.get(name);
                String kind=clause.startsWith("CHECK")?"c":clause.startsWith("UNIQUE")?"u":clause.startsWith("FOREIGN KEY")?"f":"unsupported";
                boolean deferred=clause.endsWith("DEFERRABLE INITIALLY DEFERRED");
                if(observed==null||!observed.kind().equals(kind)||!observed.validated()||observed.deferrable()!=deferred||observed.deferred()!=deferred)fail(owner,"Constraint identity/type/validation mismatch: "+entry.getKey()+"/"+name);
                if(kind.equals("u")&&!observed.columns().equals(quoted(clause)))fail(owner,"Unique-key columns differ: "+entry.getKey()+"/"+name);
                if(kind.equals("f")) {
                    var match=Pattern.compile("^FOREIGN KEY \\((.*?)\\) REFERENCES (\"(?:[^\"]|\"\")+\")\\.(\"(?:[^\"]|\"\")+\") \\((.*?)\\).*$").matcher(clause);
                    if(!match.matches()||!observed.columns().equals(quoted(match.group(1)))||!observed.target().equals(quoted(match.group(2)).getFirst()+"/"+quoted(match.group(3)).getFirst())||!observed.targetColumns().equals(quoted(match.group(4))))fail(owner,"Foreign key target/columns differ: "+entry.getKey()+"/"+name);
                }
            }
            if(!expectedNames.equals(actual.keySet()))fail(owner,"Unexpected/missing owned constraints: "+entry.getKey());
        }
    }
    private record Constraint(String kind,boolean validated,boolean deferrable,boolean deferred,List<String> columns,String target,List<String> targetColumns) { }
    private static List<String> strings(java.sql.Array array) throws SQLException {if(array==null)return List.of();return Arrays.stream((Object[])array.getArray()).map(Object::toString).toList();}
    private static List<String> quoted(String text){List<String> names=new ArrayList<>();var matches=Pattern.compile("\"((?:[^\"]|\"\")*)\"").matcher(text);while(matches.find())names.add(matches.group(1).replace("\"\"","\""));return names;}
    private static String type(String sql){return switch(sql){case "UUID"->"uuid";case "TEXT"->"text";case "BIGINT"->"bigint";case "INTEGER"->"integer";case "SMALLINT"->"smallint";case "BOOLEAN"->"boolean";case "NUMERIC"->"numeric";case "REAL"->"real";case "DOUBLE PRECISION"->"double precision";case "BYTEA"->"bytea";case "DATE"->"date";case "TIMESTAMPTZ(6)"->"timestamp(6) with time zone";case "TIMESTAMP(6)"->"timestamp(6) without time zone";case "TIME(6)"->"time(6) without time zone";default->throw new IllegalArgumentException("Unsupported schema SQL type: "+sql);};}
    private static void fail(String owner,String message){throw new IllegalStateException("Installed schema is incompatible for "+owner+": "+message);}
}
