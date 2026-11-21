// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import static org.vernac.experiments.tenancy.AuthorizationEntryProbe.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;

/** Test-only application-owned filter chain using the production terminal classifier. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableWebMvc
class AuthorizationWebProbe {
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(routes -> routes.requestMatchers("/private").authenticated().anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> write(response, Code.AUTHENTICATION_REQUIRED))
                        .accessDeniedHandler((request, response, error) -> write(response, Code.ACCESS_DENIED)))
                .build(); // CSRF intentionally remains enabled; no replacement production chain is installed.
    }

    record PublicError(String code, String message) {}
    static void write(HttpServletResponse response, Code code) throws IOException {
        var reply = org.vernac.runtime.http.inbound.InboundHttpErrors.defaultReply(Guard.productionFailure(code));
        response.setStatus(reply.status()); response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + reply.code() + "\",\"message\":\"" + reply.message() + "\"}");
    }
    @RestController
    static class Controller {
        final Inner inner;
        Controller(Inner inner) { this.inner = inner; }
        @RequestMapping(path = {"/api", "/mvc"}, method = {RequestMethod.GET, RequestMethod.POST})
        String call() {
            // Preverified fixture scope, NOT browser/header-derived tenant resolution.
            try (var ignored = TenantContext.openVerified(TenantId.of(PostgresFixture.A))) {
                return inner.execute(new InvoiceId(PostgresFixture.INVOICE_A));
            }
        }
        @GetMapping("/private") String privateRoute() { return "private"; }
    }
    @ControllerAdvice
    static class Errors {
        final Trace trace;
        Errors(Trace trace) { this.trace = trace; }
        @ExceptionHandler(Failure.class)
        Object auth(Failure failure, jakarta.servlet.http.HttpServletRequest request) {
            var error = Guard.productionFailure(failure.code);
            var defaults = org.vernac.runtime.http.inbound.InboundHttpErrors.defaultReply(error);
            if (request.getRequestURI().equals("/mvc")) {
                return org.vernac.runtime.http.inbound.MvcResponses.redisplay(() -> {
                    trace.genericErrorHooks++; throw new AssertionError("Authorization must not redisplay");
                }, defaults, org.vernac.runtime.http.inbound.MvcInputs.empty(), "access-denied", error);
            }
            return org.vernac.runtime.http.inbound.InboundHttpErrors.respond(error, (endpoint, cause) -> {
                trace.genericErrorHooks++; return new org.vernac.runtime.http.inbound.InboundHttpErrors.Reply(422, "CUSTOM", "Custom policy");
            });
        }

        @ExceptionHandler(Exception.class)
        @ResponseBody
        ResponseEntity<PublicError> generic(Exception failure) {
            trace.genericErrorHooks++;
            return ResponseEntity.internalServerError().body(new PublicError("GENERIC_FAILURE", "The operation failed."));
        }
    }
}
