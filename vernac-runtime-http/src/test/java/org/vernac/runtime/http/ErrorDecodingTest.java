// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.json.DtoContractException;
import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.runtime.http.ErrorCondition.*;

class ErrorDecodingTest {
    private HttpResponseData response(String body, String contentType) {
        return new HttpResponseData(401, HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (k,v) -> true),
                body.getBytes(StandardCharsets.UTF_8));
    }
    @Test void unavailableDataStaysUnavailableThroughNegationButStatusCanMatch() {
        var error = HttpJson.errorBody(response("{ broken", "application/json"), String.class);
        assertTrue(error.value().isEmpty());
        assertTrue(error.decodingFailure().isPresent());
        var condition = error.test(value -> value.equals("BLOCKED"));
        assertEquals(UNAVAILABLE, condition);
        assertFalse(condition.not().matches());
        assertFalse(TRUE.and(condition.not()).matches());
        assertTrue(TRUE.or(condition).matches());
        assertEquals(FALSE, FALSE.and(condition));
        assertEquals(UNAVAILABLE, FALSE.or(condition));
        assertEquals(FALSE, TRUE.not());
        assertEquals(TRUE, FALSE.not());
    }
    @Test void validErrorDataIsAvailableAndJsonMustBeCompleteAndHaveCorrectMediaType() {
        assertTrue(HttpJson.errorBody(response("\"BLOCKED\"", "application/problem+json; charset=utf-8"), String.class)
                .test(value -> value.equals("BLOCKED")).matches());
        for (String body : List.of("", "null", "\"ok\" \"extra\"")) {
            assertThrows(DtoContractException.class, () -> HttpJson.read(response(body,"application/json"), String.class));
        }
        assertThrows(DtoContractException.class, () -> HttpJson.read(response("\"ok\"", "text/html"), String.class));
        assertThrows(DtoContractException.class, () -> HttpJson.read(response("\"ok\"", ""), String.class));
    }
    @Test void responseBytesAndDiagnosticsDoNotExposeMutableStateOrPayloadInToString() {
        var response = response("\"secret\"", "application/json");
        byte[] bytes = response.body(); bytes[0] = 0;
        assertEquals("secret", HttpJson.read(response, String.class));
        assertFalse(response.toString().contains("secret"));
    }
}
