// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.net.http.HttpHeaders;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/** Call-local response for decoding/error translation. Never attached to exceptions. */
@NullMarked
public final class HttpResponseData {
    private final int status;
    private final HttpHeaders headers;
    private final byte[] body;

    public HttpResponseData(int status, HttpHeaders headers, byte[] body) {
        if (status < 100 || status > 599) throw new IllegalArgumentException("Invalid HTTP status");
        this.status = status;
        this.headers = Objects.requireNonNull(headers);
        this.body = body.clone();
    }
    public int status() { return status; }
    public HttpHeaders headers() { return headers; }
    public byte[] body() { return body.clone(); }
    public Map<String, String> failureDetails() {
        var details = new LinkedHashMap<String, String>();
        details.put("http.status", Integer.toString(status));
        headers.firstValue("Retry-After").ifPresent(value -> details.put("http.retryAfter", value));
        return Map.copyOf(details);
    }
    /** Select a known-safe correlation header explicitly; no wildcard header capture. */
    public Map<String, String> failureDetails(String correlationHeader) {
        var details = new LinkedHashMap<>(failureDetails());
        headers.firstValue(correlationHeader).ifPresent(value -> details.put("http.correlationId", value));
        return Map.copyOf(details);
    }
    @Override public String toString() {
        return "HttpResponseData[status=" + status + ", bodyBytes=" + body.length + "]";
    }
}
