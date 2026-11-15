// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import java.io.IOException;
import java.util.Objects;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Minimal accessible login form, rendered after Spring CSRF has supplied its masked token. */
public final class IdentityLoginPageFilter extends OncePerRequestFilter {
    private final IdentityLocalReturnTarget returns;
    public IdentityLoginPageFilter(IdentityLocalReturnTarget returns) { this.returns=Objects.requireNonNull(returns); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(!"GET".equals(request.getMethod()) || !"/identity/login".equals(request.getServletPath())) {
            chain.doFilter(request,response);return;
        }
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Content-Security-Policy","default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options","nosniff");
        Object attribute=request.getAttribute(CsrfToken.class.getName());
        if(!(attribute instanceof CsrfToken csrf)) {
            response.setStatus(503);response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Identity form unavailable");return;
        }
        String action=request.getContextPath()+"/identity/login";
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Sign in</title></head><body><main><h1>Sign in</h1>
                <form method="post" action="%s">
                <input type="hidden" name="%s" value="%s">
                <input type="hidden" name="returnTo" value="%s">
                <p><label for="address">Email address</label><br><input id="address" name="address" type="email" autocomplete="username" maxlength="320" required></p>
                <p><label for="password">Password</label><br><input id="password" name="password" type="password" autocomplete="current-password" maxlength="2048" required></p>
                <button type="submit">Sign in</button></form></main></body></html>
                """.formatted(escape(action),escape(csrf.getParameterName()),escape(csrf.getToken()),
                        escape(returns.resolve(request.getParameter("returnTo")))));
    }
    private static String escape(String text) {
        return text.replace("&","&amp;").replace("\"","&quot;").replace("'","&#39;").replace("<","&lt;").replace(">","&gt;");
    }
}
