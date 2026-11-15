// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import java.io.IOException;
import java.util.Objects;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Explicitly before security-context loading, form parsing and lazy CSRF/session creation. */
public final class IdentityRequestAdmissionFilter extends OncePerRequestFilter {
    private final IdentityRequestAdmission admission;
    public IdentityRequestAdmissionFilter(IdentityRequestAdmission admission) { this.admission=Objects.requireNonNull(admission); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        final IdentityRequestAdmission.Decision decision;
        try { decision=Objects.requireNonNull(admission.tryAcquire()); }
        catch(RuntimeException unavailable) {
            response.setHeader("Cache-Control","no-store");response.setStatus(503);response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Identity ingress unavailable");return;
        }
        if(!decision.admitted()) {
            response.setHeader("Cache-Control","no-store");response.setStatus(429);response.setContentType("text/plain;charset=UTF-8");
            response.setHeader("Retry-After",Integer.toString(decision.retryAfterSeconds()));
            response.getWriter().write("Identity ingress temporarily throttled");return;
        }
        chain.doFilter(request,response);
    }
}
