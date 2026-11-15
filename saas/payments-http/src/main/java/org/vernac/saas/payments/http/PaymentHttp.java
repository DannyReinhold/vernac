// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.http;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.vernac.saas.payments.PaymentFailure;

/** Bounded provider transport, with fixed production origins and no implicit retry/fallback/logging. */
public final class PaymentHttp implements AutoCloseable {
    public enum Provider { MOLLIE, STRIPE }
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_BODY = 256 * 1024;
    private final OkHttpClient client;
    private final URI base;
    private final Provider provider;
    private final String key;
    private final Semaphore permits = new Semaphore(4);
    public PaymentHttp(Provider provider, String apiKey) { this(provider, apiKey, production(provider), Duration.ofSeconds(15)); }
    // Separate loopback-only test construction; never configurable through application/provider properties.
    public static PaymentHttp loopbackForTests(Provider provider, String apiKey, URI endpoint, Duration timeout) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)
            throw fail(PaymentFailure.Code.CONFIGURATION);
        return new PaymentHttp(provider, apiKey, endpoint, timeout);
    }
    private PaymentHttp(Provider provider, String apiKey, URI endpoint, Duration timeout) {
        try {
            this.provider = Objects.requireNonNull(provider); this.base = Objects.requireNonNull(endpoint);
            if (apiKey == null || apiKey.isBlank() || apiKey.length() > 4096 || apiKey.chars().anyMatch(c -> c <= 32 || c >= 127)
                    || timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(15)) > 0) throw fail(PaymentFailure.Code.CONFIGURATION);
            this.key = apiKey;
            client = new OkHttpClient.Builder().callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .connectTimeout(3, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                    .addNetworkInterceptor(chain -> {
                        var response = chain.proceed(chain.request());
                        if (response.code() == 503 && response.header("Retry-After") != null && response.header("Retry-After").matches("0+")) return response.newBuilder().header("Retry-After", "1").build();
                        return response;
                    }).build();
        } catch (RuntimeException invalid) { throw fail(PaymentFailure.Code.CONFIGURATION); }
    }
    public JsonNode stripeGet(String path, String apiVersion) { return exchange("GET", path, null, Map.of(), Map.of("Stripe-Version", apiVersion)); }
    public JsonNode get(String path) { return exchange("GET", path, null, Map.of(), Map.of()); }
    public JsonNode post(String path, UUID operation, Map<String, Object> values) { return exchange("POST", path, Objects.requireNonNull(operation), values, Map.of()); }
    public JsonNode stripePost(String path, UUID operation, Map<String, String> form, String apiVersion) {
        if (provider != Provider.STRIPE || apiVersion == null || !apiVersion.matches("[a-zA-Z0-9.\\-]{1,80}")) throw fail(PaymentFailure.Code.CONFIGURATION);
        return exchange("POST", path, Objects.requireNonNull(operation), new LinkedHashMap<>(form), Map.of("Stripe-Version", apiVersion));
    }
    private JsonNode exchange(String method, String path, UUID operation, Map<String, ?> values, Map<String, String> headers) {
        if (path == null || !path.matches("[a-zA-Z0-9_/-]{1,300}") || path.startsWith("/") || path.contains("..") || path.contains("//")) throw fail(PaymentFailure.Code.CONFIGURATION);
        if (!permits.tryAcquire()) throw fail(PaymentFailure.Code.UNAVAILABLE);
        try {
            var request = new Request.Builder().url(base.resolve(path).toString()).header("Authorization", "Bearer " + key).header("Accept", "application/json");
            headers.forEach(request::header);
            if (method.equals("POST")) {
                request.header("Idempotency-Key", operation.toString());
                RequestBody body;
                if (provider == Provider.MOLLIE) body = RequestBody.create(JSON.writeValueAsBytes(values), MediaType.get("application/json; charset=utf-8"));
                else {
                    var form = new FormBody.Builder(StandardCharsets.UTF_8);
                    values.forEach((name, value) -> form.add(name, Objects.toString(value)));
                    body = form.build();
                }
                if (body.contentLength() > MAX_BODY) throw fail(PaymentFailure.Code.CONFIGURATION);
                request.post(body);
            }
            try (var response = client.newCall(request.build()).execute()) {
                int status = response.code();
                if (status == 401 || status == 403) throw fail(PaymentFailure.Code.CONFIGURATION);
                if (status == 400 || status == 402 || status == 422) throw fail(PaymentFailure.Code.REJECTED);
                if (status < 200 || status >= 300) throw fail(method.equals("POST") ? PaymentFailure.Code.OUTCOME_UNKNOWN : PaymentFailure.Code.UNAVAILABLE);
                if (response.body() == null) throw fail(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE);
                try (var input = response.body().byteStream()) {
                    byte[] bytes = input.readNBytes(MAX_BODY + 1);
                    if (bytes.length > MAX_BODY) throw fail(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE);
                    JsonNode json = JSON.readTree(bytes);
                    if (json == null || !json.isObject()) throw fail(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE);
                    return json;
                }
            }
        } catch (PaymentFailure safe) { throw safe; }
        catch (IOException | RuntimeException unknown) { throw fail(method.equals("POST") ? PaymentFailure.Code.OUTCOME_UNKNOWN : PaymentFailure.Code.UNAVAILABLE); }
        finally { permits.release(); }
    }
    public static String requiredText(JsonNode value, String name) {
        JsonNode field = value.get(name);
        if (field == null || !field.isTextual() || field.textValue().isEmpty() || field.textValue().length() > 4096) throw fail(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE);
        return field.textValue();
    }
    public static long requiredLong(JsonNode value, String name) {
        JsonNode field = value.get(name);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong() || field.longValue() < 0) throw fail(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE);
        return field.longValue();
    }
    public static JsonNode parseNotice(String body) {
        try { JsonNode json = JSON.readTree(body); if (json == null || !json.isObject()) throw fail(PaymentFailure.Code.INVALID_NOTIFICATION); return json; }
        catch (IOException | RuntimeException invalid) { throw fail(PaymentFailure.Code.INVALID_NOTIFICATION); }
    }
    private static URI production(Provider provider) {
        return URI.create(provider == Provider.MOLLIE ? "https://api.mollie.com/v2/" : "https://api.stripe.com/v1/");
    }
    private static PaymentFailure fail(PaymentFailure.Code code) { return new PaymentFailure(code); }
    @Override public void close() { client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
    @Override public String toString() { return "PaymentHttp[REDACTED]"; }
}
