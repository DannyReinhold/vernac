// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.json.DtoContractException;
import org.vernac.runtime.json.MappingContractException;

/** Local JSON codec. Generated DTO annotations supply strict field and PATCH semantics. */
@NullMarked
public final class HttpJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private HttpJson() { }

    public static byte[] write(Object dto) {
        Objects.requireNonNull(dto);
        try {
            return MAPPER.writeValueAsBytes(dto);
        } catch (JsonProcessingException ex) {
            throw contractFailure(ex, "Could not encode JSON request");
        }
    }

    public static <T> T read(HttpResponseData response, Class<T> type) {
        Objects.requireNonNull(type);
        String mediaType = response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!(mediaType.equals("application/json") || (mediaType.startsWith("application/") && mediaType.endsWith("+json")))) {
            throw new DtoContractException("$", "Expected application/json or application/*+json response");
        }
        try {
            T result = MAPPER.readValue(response.body(), type);
            if (result == null) throw new DtoContractException("$", "Expected non-null JSON response");
            return result;
        } catch (IOException ex) {
            throw contractFailure(ex, "Invalid JSON response");
        }
    }

    public static <T> DecodedError<T> errorBody(HttpResponseData response, Class<T> type) {
        try {
            return DecodedError.available(read(response, type));
        } catch (DtoContractException ex) {
            return DecodedError.unavailable(ex);
        }
    }

    private static RuntimeException contractFailure(Exception error, String message) {
        // Generated codecs wrap their contract exception in JsonMappingException.
        // Preserve useful field paths, but do not retain Jackson source excerpts.
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof DtoContractException ex) return ex;
            if (cause instanceof MappingContractException ex) return ex;
            if (cause instanceof DomainValidationException ex) return ex;
            if (cause instanceof RuntimeException ex) return ex;
        }
        return new DtoContractException("$", message);
    }
}
