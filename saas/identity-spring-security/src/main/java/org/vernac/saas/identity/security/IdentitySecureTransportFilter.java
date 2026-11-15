// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** No request-host-derived redirect; proxy trust must be configured outside the chain. */
final class IdentitySecureTransportFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if (!request.isSecure()) {
            response.setHeader("Cache-Control","no-store");response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");response.getWriter().write("Secure transport required");
            return;
        }
        chain.doFilter(request,response);
    }
}
