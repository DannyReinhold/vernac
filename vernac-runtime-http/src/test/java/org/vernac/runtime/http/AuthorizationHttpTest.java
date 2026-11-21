// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.authorization.AuthorizationException;
import org.vernac.runtime.http.inbound.*;

class AuthorizationHttpTest {
    @Test void sharedClassifierKeepsAuthorizationOutsideGenericDomainPolicies() {
        int[] statuses = {401, 403, 403, 500, 503};
        var codes = AuthorizationException.Code.values();
        for (int index = 0; index < codes.length; index++) {
            var error = new AuthorizationException(codes[index]);
            var reply = InboundHttpErrors.defaultReply(error);
            assertEquals(statuses[index], reply.status()); assertEquals(codes[index].name(), reply.code());
            assertFalse(InboundHttpErrors.matches(error, "domainrejection"));
            assertFalse(InboundHttpErrors.matches(error, "validation"));
            assertEquals(reply, InboundHttpErrors.withStatus(error, 422));
            assertEquals(reply, InboundHttpErrors.defaultReply(InboundHttpErrors.invocation("example.Endpoint", error)));
        }
        var wrapped = new RuntimeException("unrelated wrapper", new AuthorizationException(AuthorizationException.Code.ACCESS_DENIED));
        assertEquals("INTERNAL_ERROR", InboundHttpErrors.defaultReply(wrapped).code());
    }
    @Test void responseCannotInvokeCustomErrorHandlersOrExposeRawContext() {
        var called = new AtomicInteger();
        var logger = Logger.getLogger(InboundHttpErrors.class.getName());
        var records = new ArrayList<LogRecord>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        boolean parents = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false); logger.addHandler(handler);
        try {
            var error = InboundHttpErrors.invocation("seeded-endpoint-with-sensitive-value",
                    new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE));
            var response = InboundHttpErrors.respond(error, (endpoint, failure) -> {
                called.incrementAndGet(); return new InboundHttpErrors.Reply(422, "CUSTOM", "Not permitted");
            });
            assertEquals(503, response.getStatusCode().value());
            assertEquals("AUTHORIZATION_UNAVAILABLE", response.getBody().code()); assertEquals(0, called.get());
            assertEquals(1, records.size());
            for (var record : records) {
                assertNull(record.getThrown()); assertFalse(record.getMessage().contains("seeded"));
                assertEquals(List.of("AUTHORIZATION_UNAVAILABLE"), List.of(record.getParameters()));
            }
        } finally { logger.removeHandler(handler); logger.setUseParentHandlers(parents); }
    }
    @Test void mvcAuthorizationIsTerminalAndDoesNotRedisplayOrEchoInputsAndModel() {
        var denied = new AuthorizationException(AuthorizationException.Code.ACCESS_DENIED);
        var overridden = new InboundHttpErrors.Reply(422, "CUSTOM", "Custom error");
        var called = new AtomicInteger();
        var redisplay = MvcResponses.redisplay(() -> {
            called.incrementAndGet(); throw new AssertionError("A denied operation must not reload a page");
        }, overridden, MvcInputs.empty(), "access-denied", denied);
        assertEquals(0, called.get()); assertEquals("access-denied", redisplay.getViewName());
        assertEquals(403, redisplay.getStatus().value()); assertFalse(redisplay.getModel().containsKey("inputs"));
        assertEquals("ACCESS_DENIED", ((InboundHttpErrors.Body) redisplay.getModel().get("error")).code());
        var error = MvcResponses.error("access-denied", overridden, MvcInputs.empty(), denied);
        assertEquals(403, error.getStatus().value()); assertEquals(redisplay.getModel(), error.getModel());
        var failure = MvcResponses.failure("access-denied", MvcInputs.empty(), denied);
        assertEquals(403, failure.getStatus().value());
    }
}
