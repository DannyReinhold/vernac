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
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;

/** Test-only application-owned filter chain and proposed shared terminal classifier. */
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
    static PublicError publicError(Code code) { return new PublicError(code.name(), "The operation could not be authorized."); }
    static void write(HttpServletResponse response, Code code) throws IOException {
        response.setStatus(code.status); response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code.name() + "\",\"message\":\"" + publicError(code).message() + "\"}");
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
            if (request.getRequestURI().equals("/mvc")) {
                var view = new ModelAndView("access-denied");
                view.setStatus(org.springframework.http.HttpStatus.valueOf(failure.code.status));
                view.addObject("code", failure.code.name()); return view;
            }
            return ResponseEntity.status(failure.code.status).body(publicError(failure.code));
        }
        @ExceptionHandler(Exception.class)
        @ResponseBody
        ResponseEntity<PublicError> generic(Exception failure) {
            trace.genericErrorHooks++;
            return ResponseEntity.internalServerError().body(new PublicError("GENERIC_FAILURE", "The operation failed."));
        }
    }
}
