// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.json.DtoContractException;
import static org.junit.jupiter.api.Assertions.*;

class HttpRequestsTest {
    @Test void encodesEachValueAndPreservesBasePathAndOptionalAbsence() {
        var request = HttpRequests.at(URI.create("http://localhost:8080/api/v1/"), "/items/{key}")
                .path("key", "ä /?+%#")
                .query("search key", Optional.of("a&b=c + ä"))
                .query("missing", Optional.empty())
                .header("X-Optional", Optional.empty())
                .build("GET");
        assertEquals("/api/v1/items/%C3%A4%20%2F%3F%2B%25%23", request.uri().getRawPath());
        assertEquals("search%20key=a%26b%3Dc%20%2B%20%C3%A4", request.uri().getRawQuery());
        assertTrue(request.headers().firstValue("X-Optional").isEmpty());
    }
    @Test void malformedHeaderValuesAreReportedWithoutLeakingTheValue() {
        var error = assertThrows(DtoContractException.class, () -> HttpRequests.at(URI.create("http://localhost"), "/")
                .header("X-Value", Optional.of("secret\r\ninjected: value")).build("GET"));
        assertFalse(error.toString().contains("secret"));
        assertNull(error.getCause());
    }
    @Test void rejectsMalformedBasesUnboundSlotsAndDotSegments() {
        for (String base : new String[]{"/relative", "ftp://localhost", "http://u:secret@localhost", "http://localhost?secret=x"})
            assertThrows(DtoContractException.class, () -> HttpRequests.at(URI.create(base), "/"));
        assertThrows(DtoContractException.class, () -> HttpRequests.at(URI.create("http://localhost"), "/{id}").build("GET"));
        assertThrows(DtoContractException.class, () -> HttpRequests.at(URI.create("http://localhost"), "/{id}").path("id", ".."));
    }
}
