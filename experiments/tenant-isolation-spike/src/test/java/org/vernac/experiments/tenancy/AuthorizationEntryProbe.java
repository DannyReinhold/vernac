// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.DomainChecks;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;

/** Test-only body-guard sketch. NOT a production authorization API or generated output. */
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
    record Identity(Actor actor, TenantId tenant) {}

    sealed interface Requirement permits Leaf, All, Any, Mode {}
    record Leaf(Permission permission, Object resource) implements Requirement {}
    record All(List<Requirement> children) implements Requirement {}
    record Any(List<Requirement> children) implements Requirement {}
    enum Mode implements Requirement { PUBLIC, AUTHENTICATED }

    /** Trusted mapper, replaceable only by infrastructure, not the ordinary behavior Access. */
    interface ActorMapper { Actor map(Authentication authentication); }

    static final class Guard {
        private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
        private final ActorMapper mapper;
        private final AuthorizationManager<Call> policy;
        Guard(ActorMapper mapper, AuthorizationManager<Call> policy) { this.mapper = mapper; this.policy = policy; }

        <T> T invoke(String operation, Requirement requirement, Supplier<T> body) {
            if (requirement != Mode.PUBLIC && (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive())) {
                throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR);
            }
            Identity previous = CURRENT.get();
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Actor actor = verifiedActor(authentication);
            TenantId tenant = TenantContext.currentTenant().orElse(null);
            Identity identity = new Identity(actor, tenant);
            if (previous != null && !previous.equals(identity)) throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR);
            CURRENT.set(identity);
            try {
                if (requirement != Mode.PUBLIC) {
                    if (actor == null) throw new Failure(Code.AUTHENTICATION_REQUIRED);
                    if (requirement != Mode.AUTHENTICATED) {
                        if (tenant == null) throw new Failure(Code.SCOPE_REQUIRED);
                        TenantContext.bindToCurrentTransaction();
                        if (policy == null) throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR);
                        if (!evaluate(operation, identity, authentication, requirement)) throw new Failure(Code.ACCESS_DENIED);
                    }
                }
                return body.get();
            } finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        }

        private Actor verifiedActor(Authentication authentication) {
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) return null;
            if (mapper == null) throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR);
            try {
                Actor actor = mapper.map(authentication);
                if (actor == null) throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR);
                return actor;
            } catch (Failure failure) { throw failure; }
            catch (RuntimeException failure) { throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR); }
        }

        private boolean evaluate(String operation, Identity identity, Authentication authentication, Requirement tree) {
            if (tree instanceof Leaf leaf) {
                try {
                    var decision = policy.authorize(() -> authentication,
                            new Call(operation, identity.actor(), identity.tenant(), leaf.permission(), leaf.resource()));
                    if (decision == null) throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR);
                    return decision.isGranted();
                } catch (Failure failure) { throw failure; }
                catch (RuntimeException failure) { throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR); }
            }
            List<Requirement> children = tree instanceof All all ? all.children() : tree instanceof Any any ? any.children() : List.of();
            if (children.isEmpty()) throw new Failure(Code.AUTHORIZATION_CONFIGURATION_ERROR);
            boolean all = tree instanceof All;
            for (Requirement child : children) {
                boolean granted = evaluate(operation, identity, authentication, child);
                if (all && !granted) return false;
                if (!all && granted) return true;
            }
            return all;
        }
        static boolean contextPresent() { return CURRENT.get() != null; }
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
