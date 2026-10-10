package org.vernac.maven;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.vernac.runtime.jdbc.ScalarCodec;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pgjdbc transport, including repeated prepared statements and both wire modes. */
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
class ScalarStoragePostgresTest {
    @Test void textTransport() throws Exception { roundTrips("0"); }
    @Test void binaryTransport() throws Exception { roundTrips("1"); }

    private void roundTrips(String threshold) throws Exception {
        Properties p = new Properties();
        for (String key : List.of("user", "password")) {
            String value = System.getenv("VERNAC_PG_TEST_" + key.toUpperCase(Locale.ROOT));
            if (value != null) p.setProperty(key,value);
        }
        p.setProperty("prepareThreshold", threshold);
        p.setProperty("binaryTransfer", "true");
        try (var connection = DriverManager.getConnection(System.getenv("VERNAC_PG_TEST_URL"), p)) {
            try (var s = connection.createStatement()) {
                s.execute("CREATE TEMP TABLE scalar_probe (d DOUBLE PRECISION, f REAL, t TIMESTAMPTZ(6), n SMALLINT)");
                s.execute("SET extra_float_digits = 3");
            }
            try (var insert = connection.prepareStatement("INSERT INTO scalar_probe VALUES (?, ?, ?, ?)");
                 var select = connection.prepareStatement("SELECT d, f, t, n FROM scalar_probe")) {
                List<Double> doubles = List.of(-0.0d, 0.0d, Double.MIN_VALUE, Double.MAX_VALUE,
                        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
                List<Float> floats = List.of(-0.0f, 0.0f, Float.MIN_VALUE, Float.MAX_VALUE,
                        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
                List<Instant> times = List.of(Instant.parse("0001-01-01T00:00:00Z"),
                        Instant.parse("9999-12-31T23:59:59.999999999Z"),
                        Instant.parse("1969-12-31T23:59:59.123456789Z"));
                for (int repeat=0; repeat<3; repeat++) for (int i=0; i<doubles.size(); i++) {
                    try (var clear=connection.createStatement()) { clear.execute("DELETE FROM scalar_probe"); }
                    Instant time=times.get(i%times.size());
                    Object[] parts=ScalarCodec.encode("Instant",time);
                    insert.setObject(1, doubles.get(i));
                    insert.setObject(2, floats.get(i));
                    insert.setObject(3, parts[0]);
                    insert.setObject(4, parts[1]);
                    insert.executeUpdate();
                    try (var rs=select.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(doubles.get(i), (Double)rs.getDouble(1));
                        assertEquals(floats.get(i), (Float)rs.getFloat(2));
                        assertEquals(time, ScalarCodec.decode("Instant", new Object[]{
                                rs.getObject(3,OffsetDateTime.class), rs.getInt(4)}));
                        assertFalse(rs.next());
                    }
                }
            }
        }
    }
}
