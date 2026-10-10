// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.util.*;
import org.jspecify.annotations.NullMarked;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** SQL fragments for compiler-resolved scalar bindings; values always remain parameters. */
@NullMarked
public final class ScalarQuery {
    private final MapSqlParameterSource parameters = new MapSqlParameterSource();
    private int next;
    public MapSqlParameterSource parameters() { return parameters; }

    public String compare(String scalar, List<String> columns, String operator, Object value) {
        Objects.requireNonNull(value, "Query parameter must not be null");
        if (!Set.of("=", "!=", "<", "<=", ">", ">=").contains(operator)) throw new IllegalArgumentException("Unknown comparison");
        Object[] parts=ScalarCodec.encode(scalar,value);
        if(parts.length!=columns.size()) throw new IllegalArgumentException("Scalar component mismatch");
        List<String> left=new ArrayList<>(),right=new ArrayList<>();
        for(int i=0;i<parts.length;i++) {
            String parameter="q"+next++;
            parameters.addValue(parameter,parts[i]);
            left.add(q(columns.get(i)));
            right.add(":"+parameter);
            if(parts[i] instanceof String) {
                left.set(i,left.get(i)+" COLLATE \"C\"");
                right.set(i,"CAST("+right.get(i)+" AS text) COLLATE \"C\"");
            }
        }
        if(operator.equals("=") || operator.equals("!=")) {
            String equality;
            if(Set.of("float","Float","double","Double").contains(scalar)) {
                String sqlType=scalar.equals("float") || scalar.equals("Float") ? "real" : "double precision";
                String send=sqlType.equals("real") ? "float4send" : "float8send";
                String l=left.getFirst(),r="CAST("+right.getFirst()+" AS "+sqlType+")";
                equality="(("+l+" = 'NaN'::"+sqlType+" AND "+r+" = 'NaN'::"+sqlType+") OR "
                        +send+"("+l+") = "+send+"("+r+"))";
            } else {
                List<String> terms=new ArrayList<>();
                for(int i=0;i<left.size();i++) terms.add(left.get(i)+" = "+right.get(i));
                equality="("+String.join(" AND ",terms)+")";
            }
            return operator.equals("!=") ? "NOT ("+equality+")" : equality;
        }
        // Decimal scale participates in equality, not numeric order.
        if(scalar.equals("BigDecimal")) return left.getFirst()+" "+operator+" "+right.getFirst();
        return tuple(left)+" "+operator+" "+tuple(right);
    }
    public static String order(String scalar,List<String> columns,boolean descending) {
        List<String> keys=scalar.equals("BigDecimal") ? columns.subList(0,1) : columns;
        return String.join(", ",keys.stream().map(c->q(c)+(scalar.equals("String")?" COLLATE \"C\"":"")
                +(descending?" DESC":" ASC")+" NULLS LAST").toList());
    }
    public static String presence(String column,boolean present) { return q(column)+(present?" IS NOT NULL":" IS NULL"); }
    private static String tuple(List<String> parts) { return parts.size()==1?parts.getFirst():"("+String.join(", ",parts)+")"; }
    private static String q(String name) { return "\""+name.replace("\"","\"\"")+"\""; }
}
