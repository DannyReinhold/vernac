// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.inbound;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.mapping.ResolvedMapping;
import org.vernac.language.VernacNames;

import java.util.*;

/** MVC-only checks around the shared HTTP invocation and conversion plan. */
final class MvcContracts {
    private final List<CompilerDiagnostic> errors;

    MvcContracts(List<CompilerDiagnostic> errors) {
        this.errors = errors;
    }

    private void error(SourceLocation at, String text) {
        errors.add(CompilerDiagnostic.error(at, text));
    }

    private void view(SourceLocation at, String name) {
        if (!name.matches("[\\p{L}\\p{N}_-]+(?:/[\\p{L}\\p{N}_-]+)*"))
            error(
                    at,
                    "MVC views require a logical slash-separated template name, without traversal"
                        + " or redirect/forward expressions.");
    }

    void validate(InboundHttpAdapterNode adapter) {
        if (!adapter.mvc()) {
            if (adapter.errorView().isPresent()
                    || adapter.endpoints().stream().anyMatch(e -> e.view().isPresent())
                    || adapter.errorRules().stream().anyMatch(r -> r.view().isPresent())
                    || adapter.endpoints().stream()
                            .flatMap(e -> e.errorRules().stream())
                            .anyMatch(r -> r.view().isPresent()))
                error(
                        adapter.location(),
                        "Views, redirects and redisplay require inbound mvc, not inbound http.");
            return;
        }
        if (adapter.errorView().isEmpty())
            error(
                    adapter.location(),
                    "An MVC adapter requires error view \"template\"; for safe fallback"
                        + " responses.");
        adapter.errorView().ifPresent(v -> view(adapter.location(), v));
        for (var rule : adapter.errorRules()) {
            rule.view()
                    .flatMap(InboundHttpAdapterNode.ErrorView::template)
                    .ifPresent(v -> view(rule.location(), v));
            if (rule.view().flatMap(InboundHttpAdapterNode.ErrorView::reload).isPresent())
                error(
                        rule.location(),
                        "redisplay is endpoint-specific; place this rule inside a POST endpoint.");
        }
        for (var endpoint : adapter.endpoints()) {
            if (!Set.of("GET", "POST").contains(endpoint.verb()))
                error(endpoint.location(), "MVC endpoints currently support GET and POST.");
            if (endpoint.bodyType().isPresent() || endpoint.responseType().isPresent())
                error(
                        endpoint.location(),
                        "MVC endpoints use scalar bindings and views, not JSON body/respond DTO"
                            + " clauses.");
            if (endpoint.view().isEmpty())
                error(endpoint.location(), "An MVC endpoint requires render or redirect.");
            endpoint.view()
                    .ifPresent(
                            reply -> {
                                if (reply.redirect().isPresent()) {
                                    String target = reply.redirect().get();
                                    try {
                                        var uri = java.net.URI.create(target);
                                        if (!target.startsWith("/")
                                                || target.startsWith("//")
                                                || target.contains("\\")
                                                || target.codePoints()
                                                        .anyMatch(
                                                                c ->
                                                                        Character.isISOControl(c)
                                                                                || Character
                                                                                        .isWhitespace(
                                                                                                c))
                                                || uri.isAbsolute()
                                                || uri.getRawAuthority() != null
                                                || target.contains("{"))
                                            throw new IllegalArgumentException();
                                    } catch (IllegalArgumentException bad) {
                                        error(
                                                endpoint.location(),
                                                "MVC redirects require a static application-local"
                                                    + " path; external targets and dynamic"
                                                    + " placeholders are forbidden.");
                                    }
                                } else {
                                    view(endpoint.location(), reply.template());
                                    if (endpoint.status() == 204 || endpoint.status() == 205)
                                        error(
                                                endpoint.location(),
                                                "MVC views require a response body; 204/205 are not"
                                                    + " render statuses.");
                                }
                                reply.absentView().ifPresent(v -> view(endpoint.location(), v));
                                reply.modelName()
                                        .ifPresent(
                                                name -> {
                                                    if (!VernacNames.isIdentifier(name)
                                                            || name.startsWith("__")
                                                            || Set.of(
                                                                            "inputs", "error",
                                                                            "notice", "class")
                                                                    .contains(name))
                                                        error(
                                                                endpoint.location(),
                                                                "Invalid or reserved MVC model"
                                                                    + " name: "
                                                                        + name);
                                                });
                            });
            Set<String> servletNames = new HashSet<>();
            for (var binding : endpoint.bindings()) {
                if (Set.of("query", "form").contains(binding.kind())
                        && !servletNames.add(binding.externalName()))
                    error(
                            binding.location(),
                            "Form and query bindings share the servlet parameter namespace; use"
                                + " distinct external names.");
                if (binding.kind().equals("form") && !endpoint.verb().equals("POST"))
                    error(binding.location(), "Form bindings require POST.");
                if (Set.of("_csrf", "_method").contains(binding.externalName()))
                    error(
                            binding.location(),
                            "Reserved security/transport field cannot be bound: "
                                    + binding.externalName());
                if (binding.defaultValue().isPresent()) {
                    if (!Set.of("query", "form").contains(binding.kind())
                            || binding.type().isOptional())
                        error(
                                binding.location(),
                                "Defaults require a non-optional query or form binding.");
                    try {
                        org.vernac.runtime.WireScalars.parse(
                                binding.type().name(), binding.defaultValue().get());
                    } catch (IllegalArgumentException bad) {
                        error(
                                binding.location(),
                                "Invalid default for MVC wire type " + binding.type().name());
                    }
                }
            }
            for (var rule : endpoint.errorRules())
                rule.view()
                        .flatMap(InboundHttpAdapterNode.ErrorView::template)
                        .ifPresent(v -> view(rule.location(), v));
        }
    }

    void result(
            InboundHttpAdapterNode.Endpoint endpoint,
            ResolvedMapping.Type result,
            boolean optional) {
        if (endpoint.view().isEmpty()) return;
        var reply = endpoint.view().get();
        if (reply.redirect().isEmpty() && (result != null) != reply.modelName().isPresent())
            error(
                    endpoint.location(),
                    result == null
                            ? "A void MVC usecase cannot supply a named model."
                            : "A value-returning MVC usecase requires render ... model <name>.");
        if (optional && reply.absentView().isEmpty())
            error(
                    endpoint.location(),
                    "Optional MVC results require on absent render \"template\" status 404 or"
                        + " 410.");
        if (!optional && reply.absentView().isPresent())
            error(endpoint.location(), "on absent requires an optional usecase result.");
    }

    void reloads(InboundHttpAdapterNode adapter, List<ResolvedInbound.Endpoint> endpoints) {
        for (var endpoint : endpoints)
            for (var rule : endpoint.source().errorRules()) {
                var reload = rule.view().flatMap(InboundHttpAdapterNode.ErrorView::reload);
                if (reload.isEmpty()) continue;
                var target =
                        endpoints.stream()
                                .filter(e -> e.source().name().equals(reload.get()))
                                .findFirst();
                if (!endpoint.source().verb().equals("POST")
                        || target.isEmpty()
                        || !target.get().source().verb().equals("GET")
                        || target.get().source().view().isEmpty()
                        || target.get().source().view().get().redirect().isPresent()
                        || target.get().source().view().get().absentView().isPresent()) {
                    error(
                            rule.location(),
                            "redisplay requires a named GET endpoint with a direct, non-optional"
                                + " render response in the same adapter.");
                    continue;
                }
                for (var needed : target.get().source().bindings()) {
                    if (needed.type().isOptional() || needed.defaultValue().isPresent()) continue;
                    if (endpoint.source().bindings().stream()
                            .noneMatch(
                                    b ->
                                            !b.type().isOptional()
                                                    && b.type().name().equals(needed.type().name())
                                                    && b.kind().equals(needed.kind())
                                                    && (b.kind().equals("header")
                                                            ? b.externalName()
                                                                    .equalsIgnoreCase(
                                                                            needed.externalName())
                                                            : b.externalName()
                                                                    .equals(
                                                                            needed
                                                                                    .externalName()))))
                        error(
                                rule.location(),
                                "redisplay loader '"
                                        + reload.get()
                                        + "' requires unavailable binding "
                                        + needed.kind()
                                        + "."
                                        + needed.name()
                                        + "; declare a required binding of the same wire type or"
                                        + " provide a loader default.");
                }
            }
    }
}
