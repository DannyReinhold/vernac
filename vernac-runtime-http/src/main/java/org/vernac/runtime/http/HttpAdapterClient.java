// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.json.DtoContractException;
import org.vernac.runtime.json.MappingContractException;
import org.vernac.runtime.ports.*;

/** Synchronous per-call HTTP boundary. No retry loop, transaction, logging or shared invocation state. */
@NullMarked
public final class HttpAdapterClient {
    private final HttpClient client;
    private final Duration defaultTimeout;
    private final int maxResponseBytes;

    /** The application owns the client lifecycle. Redirects must be disabled. */
    public HttpAdapterClient(HttpClient client, Duration defaultTimeout, int maxResponseBytes) {
        this.client = Objects.requireNonNull(client);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
        if (defaultTimeout.isZero() || defaultTimeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        if (maxResponseBytes < 1) throw new IllegalArgumentException("Positive response byte limit required");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("Automatic redirects must be disabled");
        this.maxResponseBytes = maxResponseBytes;
    }

    public <T> T required(AdapterInvocation invocation, Supplier<HttpRequest> request,
            Function<HttpResponseData, T> decode, HttpErrorHandler errors) {
        Objects.requireNonNull(decode);
        Objects.requireNonNull(errors);
        var response = exchange(invocation, request);
        ensureSuccess(invocation, response, errors);
        return decode(invocation, response, decode);
    }

    /** Only an explicit 404 maps to absence. Empty/null/malformed successful bodies remain errors. */
    public <T> Optional<T> optionalOn404(AdapterInvocation invocation, Supplier<HttpRequest> request,
            Function<HttpResponseData, T> decode, HttpErrorHandler errors) {
        Objects.requireNonNull(decode);
        Objects.requireNonNull(errors);
        var response = exchange(invocation, request);
        if (response.status() == 404) return Optional.empty();
        ensureSuccess(invocation, response, errors);
        return Optional.of(decode(invocation, response, decode));
    }

    /** Void operation: any 2xx succeeds and its bounded body is deliberately ignored. */
    public void execute(AdapterInvocation invocation, Supplier<HttpRequest> request, HttpErrorHandler errors) {
        Objects.requireNonNull(errors);
        var response = exchange(invocation, request);
        ensureSuccess(invocation, response, errors);
    }

    private HttpResponseData exchange(AdapterInvocation invocation, Supplier<HttpRequest> prepare) {
        Objects.requireNonNull(invocation);
        Objects.requireNonNull(prepare);
        HttpRequest request;
        try {
            request = Objects.requireNonNull(prepare.get(), "Request factory returned null");
        } catch (DtoContractException | MappingContractException | DomainValidationException ex) {
            throw new LocalIntegrationException(invocation, IntegrationCategory.INVALID_REQUEST,
                    "Request mapping or encoding failed", Map.of(), ex);
        }
        if (request.timeout().isEmpty()) {
            request = HttpRequest.newBuilder(request, (name, value) -> true).timeout(defaultTimeout).build();
        }
        // HttpRequest.timeout alone may finish at the response headers. Keep the
        // synchronous API, but bound the future for the entire buffered response.
        var pending = client.sendAsync(request, info -> new BoundedBodySubscriber(maxResponseBytes, info.statusCode()));
        try {
            var response = pending.get(timeoutNanos(request.timeout().orElse(defaultTimeout)), TimeUnit.NANOSECONDS);
            return new HttpResponseData(response.statusCode(), response.headers(), response.body());
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new IntegrationCommunicationException(invocation, IntegrationCategory.TIMEOUT,
                    "HTTP exchange timed out; remote outcome is unknown", Map.of(), ex);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IntegrationCommunicationException(invocation, IntegrationCategory.INTERRUPTED,
                    "HTTP exchange interrupted; remote outcome is unknown", Map.of(), ex);
        } catch (ExecutionException ex) {
            Throwable failure = ex.getCause();
            if (failure instanceof HttpTimeoutException timeout) {
                throw new IntegrationCommunicationException(invocation, IntegrationCategory.TIMEOUT,
                        "HTTP exchange timed out; remote outcome is unknown", Map.of(), timeout);
            }
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof BoundedBodySubscriber.LimitExceeded limit) {
                    throw new RemoteIntegrationException(invocation, IntegrationCategory.INVALID_RESPONSE,
                            "HTTP response exceeded configured byte limit",
                            Map.of("http.status", Integer.toString(limit.status)), limit);
                }
            }
            if (failure instanceof IOException communication) {
                throw new IntegrationCommunicationException(invocation, IntegrationCategory.COMMUNICATION,
                        "HTTP communication failed; remote outcome is unknown", Map.of(), communication);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Unexpected HTTP client failure", failure);
        }
    }

    private static long timeoutNanos(Duration timeout) {
        try { return timeout.toNanos(); }
        catch (ArithmeticException ex) { return Long.MAX_VALUE; }
    }

    private static void ensureSuccess(AdapterInvocation invocation, HttpResponseData response, HttpErrorHandler errors) {
        Objects.requireNonNull(errors);
        if (response.status() < 200 || response.status() >= 300) {
            throw Objects.requireNonNull(errors.failure(invocation, response), "Error handler returned null");
        }
    }

    private static <T> T decode(AdapterInvocation invocation, HttpResponseData response, Function<HttpResponseData, T> decoder) {
        try {
            T value = decoder.apply(response);
            if (value == null) throw new DtoContractException("$", "Response decoder returned null");
            return value;
        } catch (DtoContractException | MappingContractException | DomainValidationException ex) {
            throw new RemoteIntegrationException(invocation, IntegrationCategory.INVALID_RESPONSE,
                    "Response decoding or mapping failed", response.failureDetails(), ex);
        }
    }
}
