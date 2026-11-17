// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** HTTP envelope shared by the workspace forms and text download. */
@Configuration(proxyBeanMethods = false)
class WorkspaceRequestPolicy implements WebMvcConfigurer, HandlerInterceptor {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/workspace", "/documents/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");

        if (request.getQueryString() != null) throw new IllegalArgumentException();
        switch (request.getServletPath()) {
            case "/documents/create" -> fields(request, List.of("title", "text"));
            case "/documents/delete" -> fields(request, List.of("id"));
            case "/documents/export" -> {
                fields(request, List.of("id"));
                String id = request.getParameter("id");
                if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            }
        }
        return true;
    }

    private static void fields(HttpServletRequest request, List<String> names) {
        String csrf = ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getParameterName();
        for (var entry : request.getParameterMap().entrySet()) {
            if ((!names.contains(entry.getKey()) && !csrf.equals(entry.getKey())) || entry.getValue().length != 1)
                throw new IllegalArgumentException();
        }
    }
}
