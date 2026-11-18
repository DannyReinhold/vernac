// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.vernac.runtime.http.inbound.MvcFormContract;

/** Applies the account application's form policy to its explicitly selected Vernac pages. */
final class AccountBrowserForms implements WebMvcConfigurer, HandlerInterceptor {
    private final Class<?> pages;

    AccountBrowserForms(Class<?> pages) {
        this.pages = pages;
    }

    static Set<String> postPaths(Class<?> pages) {
        var paths = new HashSet<String>();
        for (var method : pages.getDeclaredMethods()) {
            var mapping = method.getAnnotation(RequestMapping.class);
            if (method.isAnnotationPresent(MvcFormContract.class) && mapping != null
                    && List.of(mapping.method()).contains(RequestMethod.POST))
                paths.addAll(List.of(mapping.path()));
        }
        return Set.copyOf(paths);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !pages.isAssignableFrom(method.getBeanType())) return true;
        var contract = method.getMethodAnnotation(MvcFormContract.class);
        if (contract == null) return true;

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");

        if (request.getQueryString() != null && (contract.query().length == 0 || request.getMethod().equals("POST")))
            throw new IllegalArgumentException();
        var allowed = new HashSet<>(List.of(contract.query()));
        allowed.addAll(List.of(contract.form()));
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf)
            allowed.add(csrf.getParameterName());
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) throw new IllegalArgumentException();
        for (String name : contract.canonicalUuids()) {
            String id = request.getParameter(name);
            if (id != null && !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        }
        return true;
    }
}
