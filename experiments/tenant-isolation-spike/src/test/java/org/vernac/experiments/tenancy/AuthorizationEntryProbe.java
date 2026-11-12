// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.vernac.runtime.DomainChecks;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;

/** Handwritten entry/policy fixtures using the production guard; NOT generated output. */
final class AuthorizationEntryProbe {
    private AuthorizationEntryProbe() {}

    enum Code {
        AUTHENTICATION_REQUIRED(401), ACCESS_DENIED(403), SCOPE_REQUIRED(403),
        AUTHORIZATION_CONFIGURATION_ERROR(500), AUTHORIZATION_UNAVAILABLE(503);
        final int status;
        Code(int status) { this.status = status; }
    }
    static final class Failure extends RuntimeException {
        final Code code;
        Failure(Code code) { super(code.name()); this.code = code; }
    }
    enum Permission { VIEW, EDIT, EXPORT, ADMINISTER }
    record InvoiceId(java.util.UUID value) {}
    record OtherId(java.util.UUID value) {}
    record Actor(String subject, Kind kind) {
        enum Kind { HUMAN, TECHNICAL }
        // No subject in implicit logging, assertion descriptions or exception messages.
        @Override public String toString() { return "Actor[redacted]"; }
    }
    record Call(String operation, Actor actor, TenantId tenant, Permission permission, Object resource) {
        @Override public String toString() { return "Call[redacted]"; }
    }

    sealed interface Requirement permits Leaf, All, Any, Mode {}
    record Leaf(Permission permission, Object resource) implements Requirement {}
    record All(List<Requirement> children) implements Requirement {}
    record Any(List<Requirement> children) implements Requirement {}
    enum Mode implements Requirement { PUBLIC, AUTHENTICATED }

    /** Trusted mapper, replaceable only by infrastructure, not the ordinary behavior Access. */
    interface ActorMapper { Actor map(Authentication authentication); }

    /** Test adapter around the production guard; no second permission/context implementation. */
    static final class Guard {
        private final org.vernac.runtime.authorization.AuthorizationGuard delegate;
        Guard(ActorMapper mapper, AuthorizationManager<Call> policy) {
            var actors = new org.vernac.runtime.authorization.spring.SpringSecurityActorResolver(auth -> {
                if (mapper == null) throw new IllegalStateException("Missing mapper");
                Actor actor = java.util.Objects.requireNonNull(mapper.map(auth));
                return java.util.Optional.of(new org.vernac.runtime.authorization.VerifiedActor("fixture", actor.subject(),
                        org.vernac.runtime.authorization.VerifiedActor.Kind.valueOf(actor.kind().name())));
            });
            delegate = new org.vernac.runtime.authorization.AuthorizationGuard(actors, request -> {
                if (policy == null) throw new IllegalStateException("Missing policy");
                var actor = new Actor(request.actor().subject(), Actor.Kind.valueOf(request.actor().kind().name()));
                String name = request.permission().qualifiedName();
                var permission = Permission.valueOf(name.substring(name.lastIndexOf('.') + 1));
                var resource = request.resource() == null ? null : request.resource().value();
                try {
                    var decision = policy.authorize(() -> SecurityContextHolder.getContext().getAuthentication(),
                            new Call(request.operation(), actor, request.tenant(), permission, resource));
                    if (decision == null) return null; // Production guard must reject abstention.
                    return decision.isGranted() ? org.vernac.runtime.authorization.PermissionDecision.ALLOW
                            : org.vernac.runtime.authorization.PermissionDecision.DENY;
                } catch (Failure failure) { throw productionFailure(failure.code); }
            });
        }
        <T> T invoke(String operation, Requirement requirement, Supplier<T> body) {
            org.vernac.runtime.authorization.AuthorizationRequirement converted;
            try { converted = convert(requirement); }
            catch (IllegalArgumentException malformed) { throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR); }
            try (var invocation = delegate.enter(operation, converted)) { return body.get(); }
            catch (org.vernac.runtime.authorization.AuthorizationException failure) {
                throw new Failure(Code.valueOf(failure.code().name()));
            }
        }
        static org.vernac.runtime.authorization.AuthorizationException productionFailure(Code code) {
            return new org.vernac.runtime.authorization.AuthorizationException(
                    org.vernac.runtime.authorization.AuthorizationException.Code.valueOf(code.name()));
        }
        private static org.vernac.runtime.authorization.AuthorizationRequirement convert(Requirement requirement) {
            if (requirement == Mode.PUBLIC) return org.vernac.runtime.authorization.AuthorizationRequirement.Mode.PUBLIC;
            if (requirement == Mode.AUTHENTICATED) return org.vernac.runtime.authorization.AuthorizationRequirement.Mode.AUTHENTICATED;
            if (requirement instanceof Leaf leaf) {
                var key = org.vernac.runtime.authorization.PermissionKey.of("example.Permissions." + leaf.permission().name());
                return leaf.resource() == null ? org.vernac.runtime.authorization.AuthorizationRequirement.permission(key)
                        : org.vernac.runtime.authorization.AuthorizationRequirement.permission(key, resource(leaf.resource()));
            }
            var children = requirement instanceof All all ? all.children() : ((Any) requirement).children();
            var converted = children.stream().map(Guard::convert).toList();
            return requirement instanceof All ? new org.vernac.runtime.authorization.AuthorizationRequirement.All(converted)
                    : new org.vernac.runtime.authorization.AuthorizationRequirement.Any(converted);
        }
        @SuppressWarnings("unchecked")
        private static <T> org.vernac.runtime.authorization.ResourceReference<T> resource(T value) {
            return org.vernac.runtime.authorization.ResourceReference.of((Class<T>) value.getClass(), value);
        }
        static boolean contextPresent() { return org.vernac.runtime.authorization.AuthorizationGuard.currentActor().isPresent(); }
    }

    static final class Trace {
        final List<String> events = new java.util.ArrayList<>();
        final List<Call> calls = new java.util.ArrayList<>();
        int genericErrorHooks;
    }
    /** The real Spring SPI, with MANDATORY tenant reads before returning a decision. */
    // Spring Security 6.4 retains check as the abstract SPI; authorize delegates to it.
    @SuppressWarnings("deprecation")
    public static class Policy implements AuthorizationManager<Call> {
        final InvoiceStore store;
        final Trace trace;
        final java.util.Map<Permission, Boolean> decisions = new java.util.EnumMap<>(Permission.class);
        Permission broken;
        boolean unavailable;
        Policy(InvoiceStore store, Trace trace) { this.store = store; this.trace = trace; }
        @Override @Transactional(propagation = Propagation.MANDATORY)
        public AuthorizationDecision check(Supplier<Authentication> authentication, Call call) {
            trace.events.add("policy:" + call.permission()); trace.calls.add(call);
            if (call.permission() == broken) {
                if (unavailable) throw new Failure(Code.AUTHORIZATION_UNAVAILABLE);
                throw new IllegalStateException("seeded-backend-secret");
            }
            // Actual scoped read; equal UUIDs in a different nominal ID are never accepted.
            boolean visible = true;
            if (call.resource() != null) {
                if (!(call.resource() instanceof InvoiceId id)) visible = false;
                else try { store.byId(id.value()); }
                catch (TenantBoundary.Failure absent) {
                    if (!"NOT_FOUND".equals(absent.getMessage())) throw absent;
                    visible = false;
                }
            } else store.visibleCount();
            return new AuthorizationDecision(visible && decisions.getOrDefault(call.permission(), false));
        }
    }

    /** Models the proposed generated public entry, without changing the compiler yet. */
    public static class Inner {
        final Guard guard;
        final InvoiceStore store;
        final Trace trace;
        Inner(Guard guard, InvoiceStore store, Trace trace) { this.guard = guard; this.store = store; this.trace = trace; }
        @Transactional
        public String execute(InvoiceId id) {
            DomainChecks.requireNonNull(id, "ExportInvoice.invoiceId");
            return guard.invoke("example.ExportInvoice", new Leaf(Permission.EXPORT, id), () -> {
                trace.events.add("validates");
                if (id.value() == null) throw new org.vernac.runtime.DomainValidationException("invalid invoice");
                trace.events.add("body");
                var invoice = store.byId(id.value()); invoice.title = "exported"; store.save(invoice);
                trace.events.add("result"); return "done";
            });
        }
    }
    public static class Outer {
        final Guard guard; final Inner inner; final InvoiceStore store;
        Outer(Guard guard, Inner inner, InvoiceStore store) { this.guard = guard; this.inner = inner; this.store = store; }
        @Transactional
        public String execute(boolean callInner, boolean catchFailure, InvoiceId id) {
            return guard.invoke("example.Outer", Mode.PUBLIC, () -> {
                var invoice = store.byId(PostgresFixture.INVOICE_A); invoice.title = "outer write"; store.save(invoice);
                if (callInner) {
                    try { return inner.execute(id); }
                    catch (Failure denied) { if (!catchFailure) throw denied; }
                }
                return "outer";
            });
        }
    }
}
