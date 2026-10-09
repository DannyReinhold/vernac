package org.vernac.runtime.jdbc;

import org.junit.jupiter.api.Test;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScalarCodecTest {
    @Test void preservesEqualityForSupportedScalarFamilies() {
        List<Object[]> cases=List.of(
            new Object[]{"BigDecimal",new BigDecimal("120.00")},new Object[]{"BigDecimal",new BigDecimal("1E+9")},
            new Object[]{"BigInteger",new BigInteger("123456789012345678901234567890")},
            new Object[]{"Instant",Instant.parse("1969-12-31T23:59:59.123456789Z")},
            new Object[]{"LocalDate",LocalDate.of(2026,10,9)},new Object[]{"LocalTime",LocalTime.of(23,59,59,999999999)},
            new Object[]{"LocalDateTime",LocalDateTime.of(2026,10,9,10,11,12,123456789)},
            new Object[]{"OffsetDateTime",OffsetDateTime.parse("2026-10-09T12:34:56.123456789+05:45")},
            new Object[]{"OffsetTime",OffsetTime.parse("12:34:56.123456789-03:30")},
            new Object[]{"ZonedDateTime",ZonedDateTime.ofLocal(LocalDateTime.of(2026,10,25,2,30,0,123456789),ZoneId.of("Europe/Berlin"),ZoneOffset.ofHours(1))},
            new Object[]{"Duration",Duration.ofSeconds(-2,999999999)},new Object[]{"Period",Period.of(-1,14,-3)},
            new Object[]{"YearMonth",YearMonth.of(2026,10)},new Object[]{"MonthDay",MonthDay.of(2,29)},
            new Object[]{"Month",Month.OCTOBER},new Object[]{"DayOfWeek",DayOfWeek.FRIDAY},new Object[]{"Year",Year.of(2026)},
            new Object[]{"ZoneOffset",ZoneOffset.ofHoursMinutesSeconds(1,2,3)},new Object[]{"ZoneId",ZoneId.of("Europe/Berlin")},
            new Object[]{"Currency",Currency.getInstance("EUR")},new Object[]{"UUID",UUID.randomUUID()},
            new Object[]{"char",'\uD800'},new Object[]{"String","Grüße 𐐀"},new Object[]{"byte",(byte)-128},new Object[]{"short",(short)-32768},
            new Object[]{"int",Integer.MIN_VALUE},new Object[]{"long",Long.MAX_VALUE},new Object[]{"float",Float.NaN},
            new Object[]{"double",-0.0d},new Object[]{"boolean",true});
        for(var c:cases) assertEquals(c[1],ScalarCodec.decode((String)c[0],ScalarCodec.encode((String)c[0],c[1])),(String)c[0]);
    }
    @Test void decimalReadRestoresScaleRatherThanDatabasePresentation() {
        assertEquals(new BigDecimal("1E+3"),ScalarCodec.decode("BigDecimal",new Object[]{new BigDecimal("1000"),-3}));
        assertThrows(ArithmeticException.class,()->ScalarCodec.decode("BigDecimal",new Object[]{new BigDecimal("1.25"),1}));
    }
    @Test void instantComponentsAreTruncatedNotRounded() {
        Object[] parts=ScalarCodec.encode("Instant",Instant.parse("2026-10-09T12:00:00.999999999Z"));
        assertEquals(OffsetDateTime.parse("2026-10-09T12:00:00.999999Z"),parts[0]);
        assertEquals(999,parts[1]);
    }
}
