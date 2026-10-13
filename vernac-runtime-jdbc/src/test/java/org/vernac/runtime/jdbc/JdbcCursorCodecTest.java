// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.vernac.runtime.pagination.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcCursorCodecTest {
    private static String key(int fill) { byte[] key=new byte[32]; Arrays.fill(key,(byte)fill); return Base64.getEncoder().encodeToString(key); }
    private static byte[] context(String query,String model,String value) {
        return JdbcCursorCodec.context(query,model,"table","title = :q0",List.of(new JdbcCursorSort("String",List.of("title"),false)),
                new MapSqlParameterSource("q0",value));
    }
    @Test void roundTripsStoredComponentsWithoutPlaintextTokens() {
        var codec=JdbcCursorCodec.configured(key(1));
        var values=List.<Object>of("Confidential-Ä-\uD83D\uDE00",new BigDecimal("1.00"),(byte)1,(short)2,3,4L,-0.0f,Double.NaN,true,
                UUID.randomUUID(),LocalDate.of(2026,10,13),LocalTime.of(1,2,3,123456000),
                LocalDateTime.of(2026,10,13,1,2,3,123456000),OffsetDateTime.parse("2026-10-13T12:00:00.123456+02:00"));
        byte[] scope=context("query","schema","filter");
        var token=codec.seal(scope,values);
        assertFalse(token.value().contains("Confidential"));
        assertEquals(values,codec.open(token,scope,values.size()));
        assertNotEquals(token,codec.seal(scope,values)); // fresh authenticated nonce
    }
    @Test void refusesModifiedWrongQueryFilterSchemaKeyAndShape() {
        var codec=JdbcCursorCodec.configured(key(1)); byte[] scope=context("query","schema","filter");
        var token=codec.seal(scope,List.of("boundary",UUID.randomUUID()));
        assertThrows(InvalidCursorException.class,()->codec.open(token,context("other","schema","filter"),2));
        assertThrows(InvalidCursorException.class,()->codec.open(token,context("query","other","filter"),2));
        assertThrows(InvalidCursorException.class,()->codec.open(token,context("query","schema","other"),2));
        assertThrows(InvalidCursorException.class,()->JdbcCursorCodec.configured(key(2)).open(token,scope,2));
        assertThrows(InvalidCursorException.class,()->codec.open(token,scope,1));
        byte[] bytes=Base64.getUrlDecoder().decode(token.value().substring(3)); bytes[20]^=1;
        var modified=PageCursor.of("v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        assertThrows(InvalidCursorException.class,()->codec.open(modified,scope,2));
        for(String invalid:List.of("v2.foo","v1.!","v1.AA","not-a-cursor","v1."+"a".repeat(1_500_000)))
            assertThrows(InvalidCursorException.class,()->codec.open(PageCursor.of(invalid),scope,2));
    }
    @Test void contextUsesCanonicalFilterEncodingAndDeclaredOrdering() {
        var a=new MapSqlParameterSource().addValue("q1",new BigDecimal("1.00")).addValue("q0","value");
        var b=new MapSqlParameterSource().addValue("q0","value").addValue("q1",new BigDecimal("1.00"));
        var sorting=List.of(new JdbcCursorSort("BigDecimal",List.of("amount","@scale:amount"),false),new JdbcCursorSort("UUID",List.of("id"),true));
        byte[] one=JdbcCursorCodec.context("q","model","table","where",sorting,a);
        assertArrayEquals(one,JdbcCursorCodec.context("q","model","table","where",sorting,b));
        b.addValue("q1",new BigDecimal("1.0"));
        assertFalse(Arrays.equals(one,JdbcCursorCodec.context("q","model","table","where",sorting,b)));
        assertFalse(Arrays.equals(one,JdbcCursorCodec.context("q","model","table","where",sorting.reversed(),a)));
        assertEquals(List.of("amount"),sorting.getFirst().orderedColumns());
    }
    @Test void configuredKeysSurviveCodecInstancesButConfigurationErrorsDoNotEchoKeys() {
        var first=JdbcCursorCodec.configured(key(1)); var second=JdbcCursorCodec.configured(key(1));
        byte[] scope=context("q","model","value");
        assertEquals(List.of("boundary"),second.open(first.seal(scope,List.of("boundary")),scope,1));
        assertSame(JdbcCursorCodec.configured(""),JdbcCursorCodec.configured(""));
        for(String invalid:List.of("secret-invalid-key",Base64.getEncoder().encodeToString(new byte[16]))) {
            var error=assertThrows(PaginationContractException.class,()->JdbcCursorCodec.configured(invalid));
            assertFalse(error.getMessage().contains(invalid));
        }
    }
}
