// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import java.io.IOException;
import java.net.*;
import java.util.Objects;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

/** Explicit login adapter. Install after CSRF; form parsing, context storage and fixation strategy are mandatory. */
public final class IdentityLoginFilter extends AbstractAuthenticationProcessingFilter {
    public IdentityLoginFilter(String path, AuthenticationManager manager,
            SecurityContextRepository contexts, SessionAuthenticationStrategy sessions,
            IdentityLocalReturnTarget returns) {
        super(request -> "POST".equals(request.getMethod()) && Objects.equals(path, request.getServletPath()),
                Objects.requireNonNull(manager));
        if (path == null || !path.matches("/[a-zA-Z0-9/_-]+")) throw new IllegalArgumentException("Invalid login path");
        Objects.requireNonNull(returns);
        setSecurityContextRepository(Objects.requireNonNull(contexts));
        setSessionAuthenticationStrategy(Objects.requireNonNull(sessions));
        setAuthenticationSuccessHandler((request, response, authentication) -> {
            response.setHeader("Cache-Control", "no-store");
            response.setStatus(HttpServletResponse.SC_SEE_OTHER);
            response.setHeader("Location", returns.resolve(request.getParameter("returnTo")));
        });
        setAuthenticationFailureHandler((request, response, failure) -> {
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("text/plain;charset=UTF-8");
            if (failure instanceof IdentityAuthenticationThrottledException throttled) {
                response.setStatus(429);
                response.setHeader("Retry-After", Integer.toString(throttled.retryAfterSeconds()));
                response.getWriter().write("Identity authentication temporarily throttled");
            } else if (failure instanceof BadCredentialsException) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Identity authentication failed");
            } else {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.getWriter().write("Identity authentication unavailable");
            }
        });
    }

    @Override public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        if (!IdentityFormFilter.parsed(request)) throw new AuthenticationServiceException("Identity form pipeline unavailable");
        var input = new IdentityLoginInput(request.getParameter("address"), transportPeer(request.getRemoteAddr()));
        var token = UsernamePasswordAuthenticationToken.unauthenticated(input, Objects.requireNonNullElse(request.getParameter("password"), ""));
        try {
            Authentication result = getAuthenticationManager().authenticate(token);
            if (result == null || !result.isAuthenticated() || !(result.getPrincipal() instanceof IdentityPrincipal)
                    || !(result.getDetails() instanceof IdentitySessionStamp) || result.getCredentials() != null)
                throw new AuthenticationServiceException("Identity authentication unavailable");
            return result;
        } finally { token.eraseCredentials(); }
    }

    static InetAddress transportPeer(String value) {
        try {
            // Only numeric transport metadata; no DNS lookups or forwarded-header trust.
            if (value != null && value.contains(":") && value.matches("[0-9a-fA-F:.]{2,45}")) return InetAddress.getByName(value);
            if (value == null) throw new IllegalArgumentException();
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException();
            byte[] bytes = new byte[4];
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
                int part = Integer.parseInt(parts[i]);
                if (part > 255) throw new IllegalArgumentException();
                bytes[i] = (byte) part;
            }
            return InetAddress.getByAddress(bytes);
        } catch (IllegalArgumentException | UnknownHostException invalid) {
            throw new AuthenticationServiceException("Identity transport source unavailable");
        }
    }

    @Override public String toString() { return "IdentityLoginFilter[REDACTED]"; }
}
