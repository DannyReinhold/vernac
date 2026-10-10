// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.vernac.compiler.persistence.ScalarMappings;
import org.vernac.runtime.jdbc.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class ScalarQueryPostgresTest {
    @Test void equalityAndInequalityMatchJavaForEveryScalarFamily() throws Exception {
        var cases=new LinkedHashMap<String,List<?>>();
        cases.put("boolean",List.of(false,true));
        cases.put("byte",List.of((byte)-128,(byte)127));
        cases.put("short",List.of((short)-32768,(short)32767));
        cases.put("int",List.of(Integer.MIN_VALUE,Integer.MAX_VALUE));
        cases.put("long",List.of(Long.MIN_VALUE,Long.MAX_VALUE));
        cases.put("char",List.of('A','\uD800','\uFFFF'));
        cases.put("String",List.of("ABC","abc","abc ","é","e\u0301","𐐀"));
        cases.put("UUID",List.of(UUID.randomUUID(),UUID.randomUUID()));
        cases.put("BigInteger",List.of(BigInteger.TEN.pow(100),BigInteger.ONE));
        cases.put("BigDecimal",List.of(new BigDecimal("1.0"),new BigDecimal("1.00"),new BigDecimal("1E+3"),new BigDecimal("1000")));
        cases.put("float",List.of(-0.0f,0.0f,Float.NaN,Float.intBitsToFloat(0x7fc00001),Float.MIN_VALUE,Float.MAX_VALUE,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY));
        cases.put("double",List.of(-0.0d,0.0d,Double.NaN,Double.longBitsToDouble(0x7ff8000000000001L),Double.MIN_VALUE,Double.MAX_VALUE,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY));
        cases.put("LocalDate",List.of(LocalDate.of(1,1,1),LocalDate.of(9999,12,31)));
        cases.put("LocalTime",List.of(LocalTime.MAX,LocalTime.MAX.minusNanos(1),LocalTime.MIDNIGHT));
        cases.put("LocalDateTime",List.of(LocalDateTime.of(2026,1,1,0,0,0,1),LocalDateTime.of(2026,1,1,0,0,0,2)));
        cases.put("Instant",List.of(Instant.ofEpochSecond(-1,999999998),Instant.ofEpochSecond(-1,999999999)));
        var offset=OffsetDateTime.parse("2026-01-01T00:00:00.123456789+01:00");
        cases.put("OffsetDateTime",List.of(offset,offset.withOffsetSameInstant(ZoneOffset.UTC)));
        cases.put("OffsetTime",List.of(OffsetTime.parse("12:00:00.000000001+01:00"),OffsetTime.parse("11:00:00.000000001Z")));
        var zone=ZonedDateTime.ofLocal(LocalDateTime.of(2026,10,25,2,30),ZoneId.of("Europe/Berlin"),ZoneOffset.ofHours(2));
        cases.put("ZonedDateTime",List.of(zone,zone.withLaterOffsetAtOverlap(),zone.withZoneSameInstant(ZoneId.of("Europe/Paris"))));
        cases.put("Duration",List.of(Duration.ofNanos(-1),Duration.ZERO,Duration.ofNanos(1)));
        cases.put("Period",List.of(Period.ofMonths(12),Period.ofYears(1)));
        cases.put("YearMonth",List.of(YearMonth.of(-1,12),YearMonth.of(2026,1)));
        cases.put("MonthDay",List.of(MonthDay.of(2,29),MonthDay.of(3,1)));
        cases.put("Year",List.of(Year.of(-1),Year.of(999999999)));
        cases.put("Month",List.of(Month.JANUARY,Month.DECEMBER));
        cases.put("DayOfWeek",List.of(DayOfWeek.MONDAY,DayOfWeek.SUNDAY));
        cases.put("Currency",List.of(Currency.getInstance("EUR"),Currency.getInstance("USD")));
        cases.put("ZoneId",List.of(ZoneId.of("UTC"),ZoneId.of("Etc/UTC")));
        cases.put("ZoneOffset",List.of(ZoneOffset.MIN,ZoneOffset.MAX));
        for(String threshold:List.of("0","1")) try(var connection=connection(threshold)) {
            var jdbc=new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection,true));
            for(var entry:cases.entrySet()) {
                String type=entry.getKey(); var parts=ScalarMappings.of(type);
                List<String> columns=java.util.stream.IntStream.range(0,parts.size()).mapToObj(i->"c"+i).toList();
                List<String> definitions=new ArrayList<>(List.of("rowid INTEGER"));
                for(int i=0;i<parts.size();i++) definitions.add(columns.get(i)+" "+parts.get(i).sqlType());
                jdbc.getJdbcTemplate().execute("CREATE TEMP TABLE probe ("+String.join(", ",definitions)+")");
                try {
                    for(int row=0;row<entry.getValue().size();row++) {
                        Object[] encoded=ScalarCodec.encode(type,entry.getValue().get(row));
                        var params=new MapSqlParameterSource("rowid",row);
                        for(int i=0;i<encoded.length;i++) params.addValue(columns.get(i),encoded[i]);
                        jdbc.update("INSERT INTO probe VALUES (:rowid, "+String.join(", ",columns.stream().map(c->":"+c).toList())+")",params);
                    }
                    jdbc.getJdbcTemplate().execute("INSERT INTO probe (rowid) VALUES (-1)");
                    for(int repeat=0;repeat<2;repeat++) for(Object parameter:entry.getValue()) for(String op:List.of("=","!=")) {
                        var query=new ScalarQuery(); String sql=query.compare(type,columns,op,parameter);
                        List<Integer> actual=jdbc.queryForList("SELECT rowid FROM probe WHERE "+sql+" ORDER BY rowid",query.parameters(),Integer.class);
                        List<Integer> expected=new ArrayList<>();
                        for(int i=0;i<entry.getValue().size();i++) if(Objects.equals(entry.getValue().get(i),parameter)==op.equals("=")) expected.add(i);
                        assertEquals(expected,actual,type+" "+op+" / transport "+threshold);
                    }
                    if(Set.of("BigDecimal","LocalTime","LocalDateTime","Instant","Duration","YearMonth","MonthDay","LocalDate","Year","int","long").contains(type)) {
                        Object pivot=entry.getValue().getFirst();
                        for(String op:List.of("<","<=",">",">=")) {
                            var query=new ScalarQuery(); String sql=query.compare(type,columns,op,pivot);
                            var actual=jdbc.queryForList("SELECT rowid FROM probe WHERE "+sql+" ORDER BY rowid",query.parameters(),Integer.class);
                            List<Integer> expected=new ArrayList<>();
                            for(int i=0;i<entry.getValue().size();i++) {
                                @SuppressWarnings("unchecked") int cmp=((Comparable<Object>)entry.getValue().get(i)).compareTo(pivot);
                                if(switch(op) { case "<"->cmp<0;case "<="->cmp<=0;case ">"->cmp>0;default->cmp>=0;}) expected.add(i);
                            }
                            assertEquals(expected,actual,type+" "+op);
                        }
                    }
                } finally { jdbc.getJdbcTemplate().execute("DROP TABLE probe"); }
            }
        }
    }
    private Connection connection(String threshold) throws Exception {
        var p=new Properties();
        for(String key:List.of("user","password")) {
            String value=System.getenv("VERNAC_PG_TEST_"+key.toUpperCase(Locale.ROOT));
            if(value!=null) p.setProperty(key,value);
        }
        p.setProperty("prepareThreshold",threshold); p.setProperty("binaryTransfer","true");
        var c=DriverManager.getConnection(System.getenv("VERNAC_PG_TEST_URL"),p);
        try(var s=c.createStatement()) { s.execute("SET extra_float_digits = 3"); }
        return c;
    }
}
