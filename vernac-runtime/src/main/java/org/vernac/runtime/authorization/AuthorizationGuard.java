// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import static org.vernac.runtime.authorization.AuthorizationException.Code.*;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;

/** Body guard; does not create transactions, authenticate requests or bind JDBC connections. */
@NullMarked
public final class AuthorizationGuard {
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();
    private final ActorResolver actors;
    private final PermissionEvaluator evaluator;

    public AuthorizationGuard(ActorResolver actors, PermissionEvaluator evaluator) {
        this.actors = Objects.requireNonNull(actors); this.evaluator = Objects.requireNonNull(evaluator);
    }

    /** Place after required-null checks and before validates/body, inside the REQUIRED entrypoint. */
    public Invocation enter(String operation, AuthorizationRequirement requirement) {
        Objects.requireNonNull(operation); Objects.requireNonNull(requirement);
        if (operation.isBlank()) throw new IllegalArgumentException("Operation identity is required");
        boolean protectedEntry = requirement != AuthorizationRequirement.Mode.PUBLIC;
        if (protectedEntry && (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())) throw failure(AUTHORIZATION_CONFIGURATION_ERROR);
        VerifiedActor actor = resolveActor();
        TenantId tenant = TenantContext.currentTenant().orElse(null);
        Invocation previous = CURRENT.get();
        if (previous != null && (!Objects.equals(actor, previous.actor) || !Objects.equals(tenant, previous.tenant)))
            throw failure(AUTHORIZATION_CONFIGURATION_ERROR);
        Invocation invocation = new Invocation(actor, tenant, previous);
        CURRENT.set(invocation);
        try {
            if (protectedEntry) {
                if (actor == null) throw failure(AUTHENTICATION_REQUIRED);
                if (tenant != null) TenantContext.bindToCurrentTransaction();
                if (requirement != AuthorizationRequirement.Mode.AUTHENTICATED) {
                    if (tenant == null) throw failure(SCOPE_REQUIRED);
                    if (!allowed(operation, actor, tenant, requirement)) throw failure(ACCESS_DENIED);
                }
            }
            return invocation;
        } catch (RuntimeException | Error failed) {
            invocation.close(); throw failed;
        }
    }

    public static Optional<VerifiedActor> currentActor() {
        Invocation invocation = CURRENT.get();
        return invocation == null ? Optional.empty() : Optional.ofNullable(invocation.actor);
    }

    private @Nullable VerifiedActor resolveActor() {
        try { return Objects.requireNonNull(actors.resolve()).orElse(null); }
        catch (RuntimeException failed) { throw technical(failed); }
    }

    private boolean allowed(String operation, VerifiedActor actor, TenantId tenant, AuthorizationRequirement requirement) {
        if (requirement instanceof AuthorizationRequirement.Permission leaf) {
            try {
                PermissionDecision decision = Objects.requireNonNull(evaluator.evaluate(
                        new PermissionRequest(operation, actor, tenant, leaf.key(), leaf.resource())));
                return decision == PermissionDecision.ALLOW;
            } catch (RuntimeException failed) { throw technical(failed); }
        }
        if (requirement instanceof AuthorizationRequirement.All all) {
            for (var child : all.children()) if (!allowed(operation, actor, tenant, child)) return false;
            return true;
        }
        if (requirement instanceof AuthorizationRequirement.Any any) {
            for (var child : any.children()) if (allowed(operation, actor, tenant, child)) return true;
            return false;
        }
        throw failure(AUTHORIZATION_CONFIGURATION_ERROR);
    }

    private static AuthorizationException technical(RuntimeException failed) {
        // Preserve only safe technical classifications; never turn provider failures into Deny.
        if (failed instanceof AuthorizationException auth && (auth.code() == AUTHORIZATION_UNAVAILABLE
                || auth.code() == AUTHORIZATION_CONFIGURATION_ERROR)) return auth;
        // Neither raw message nor cause may contain credentials, subject/resource IDs or backend text.
        return failure(AUTHORIZATION_CONFIGURATION_ERROR);
    }
    private static AuthorizationException failure(AuthorizationException.Code code) { return new AuthorizationException(code); }

    /** Immutable, thread-owned LIFO frame. Does not expose identity or tenant replacement. */
    public static final class Invocation implements AutoCloseable {
        private final @Nullable VerifiedActor actor;
        private final @Nullable TenantId tenant;
        private final @Nullable Invocation previous;
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private Invocation(@Nullable VerifiedActor actor, @Nullable TenantId tenant, @Nullable Invocation previous) {
            this.actor = actor; this.tenant = tenant; this.previous = previous;
        }
        @Override public void close() {
            if (owner != Thread.currentThread()) throw failure(AUTHORIZATION_CONFIGURATION_ERROR);
            if (closed) return;
            if (CURRENT.get() != this) throw failure(AUTHORIZATION_CONFIGURATION_ERROR);
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            closed = true;
        }
        @Override public String toString() { return "AuthorizationInvocation[redacted]"; }
    }
}
