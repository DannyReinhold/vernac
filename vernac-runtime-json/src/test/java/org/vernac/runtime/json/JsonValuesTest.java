// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.JsonFactory;
import static org.junit.jupiter.api.Assertions.*;
class JsonValuesTest {
    @Test void everyApprovedScalarHasAnExactJavaRoundtrip() throws Exception {
        var values=List.of(true,(byte)3,(short)4,5,6L,1.25f,-0.0d,'ä',"text",
            new BigDecimal("12345678901234567890.1200"),new BigDecimal("-0.000"),new BigInteger("123456789012345678901234567890"),
            UUID.randomUUID(),Currency.getInstance("EUR"),Instant.parse("2026-10-11T12:00:00.123456789Z"),
            LocalDate.of(2026,10,11),LocalTime.of(12,3,4,123456789),LocalDateTime.of(2026,10,11,12,3,4,123456789),
            OffsetDateTime.parse("2026-10-11T12:00:00.123456789+02:00"),OffsetTime.parse("12:00:00.123456789+02:00"),
            ZonedDateTime.parse("2026-10-11T12:00:00+02:00[Europe/Berlin]"),Duration.ofNanos(123456789),Period.of(1,2,3),
            Year.of(2026),YearMonth.of(2026,10),MonthDay.of(10,11),Month.OCTOBER,DayOfWeek.SUNDAY,ZoneId.of("Europe/Berlin"),ZoneOffset.ofHours(2));
        for(var value:values) {
            var type=value instanceof ZoneId && !(value instanceof ZoneOffset)?ZoneId.class:value.getClass();
            try(var parser=new JsonFactory().createParser(JsonValues.scalarNode(value).toString())) {
                assertEquals(value,JsonValues.scalar(JsonValues.readTree(parser),"$.value",type),type.getSimpleName());
            }
        }
    }
    @Test void malformedValuesDoNotGetCoercedOrExposeTheirValue() throws Exception {
        try(var parser=new JsonFactory().createParser("\"not-a-date-secret\"")) {
            var node=JsonValues.readTree(parser);
            var ex=assertThrows(DtoContractException.class,()->JsonValues.scalar(node,"$.day",LocalDate.class));
            assertEquals("$.day",ex.path());assertFalse(ex.getMessage().contains("secret"));
            assertThrows(DtoContractException.class,()->JsonValues.scalar(node,"$.enabled",Boolean.class));
        }
        assertThrows(DtoContractException.class,()->JsonValues.scalarNode(Double.POSITIVE_INFINITY));
        try(var parser=new JsonFactory().createParser("1e1000")) {
            var node=JsonValues.readTree(parser);
            assertThrows(DtoContractException.class,()->JsonValues.scalar(node,"$.number",Double.class));
        }
    }
}
