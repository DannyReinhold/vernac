// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.runtime.authorization.AuthorizationException.Code.*;
import static org.vernac.runtime.authorization.AuthorizationRequirement.*;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.vernac.runtime.tenancy.*;

class AuthorizationGuardTest {
    private final VerifiedActor human = new VerifiedActor("fixture", "seeded-subject", VerifiedActor.Kind.HUMAN);
    private final TenantId tenant = TenantId.of(UUID.randomUUID());
    private final PermissionKey view = PermissionKey.of("example.InvoicePermissions.VIEW");
    private final PermissionKey edit = PermissionKey.of("example.InvoicePermissions.EDIT");
    private final List<PermissionRequest> calls = new ArrayList<>();
    private final TransactionTemplate transaction = new TransactionTemplate(new Manager());
    private VerifiedActor actor = human;
    private PermissionDecision decision = PermissionDecision.ALLOW;
    private final AuthorizationGuard guard = new AuthorizationGuard(() -> Optional.ofNullable(actor), request -> {
        calls.add(request); return decision;
    });
    @AfterEach void noLeaks() {
        assertTrue(AuthorizationGuard.currentActor().isEmpty());
        assertTrue(TenantContext.currentTenant().isEmpty()); assertTrue(TenantContext.transactionTenant().isEmpty());
    }
    private <T> T inTenant(Supplier<T> body) {
        try (var selected = TenantContext.openVerified(tenant)) { return transaction.execute(status -> body.get()); }
    }
    private static void code(AuthorizationException.Code expected, Runnable action) {
        assertEquals(expected, assertThrows(AuthorizationException.class, action::run).code());
    }
    @Test void protectedEntryRequiresTransactionActorAndScopeBeforePolicy() {
        code(AUTHORIZATION_CONFIGURATION_ERROR, () -> guard.enter("example.Export", permission(view)));
        actor = null;
        transaction.executeWithoutResult(s -> code(AUTHENTICATION_REQUIRED, () -> guard.enter("example.Export", Mode.AUTHENTICATED)));
        actor = human;
        transaction.executeWithoutResult(s -> code(SCOPE_REQUIRED, () -> guard.enter("example.Export", permission(view))));
        assertTrue(calls.isEmpty());
    }
    @Test void publicAndAuthenticatedHaveNoPermissionSideEffects() {
        actor = null;
        try (var invocation = guard.enter("example.Register", Mode.PUBLIC)) { assertTrue(AuthorizationGuard.currentActor().isEmpty()); }
        actor = new VerifiedActor("jobs", "seeded-worker", VerifiedActor.Kind.TECHNICAL);
        transaction.executeWithoutResult(s -> {
            try (var invocation = guard.enter("example.Job", Mode.AUTHENTICATED)) {
                assertEquals(actor, AuthorizationGuard.currentActor().orElseThrow());
            }
        });
        assertTrue(calls.isEmpty());
    }
    @Test void aReachedNestedCallGetsItsOwnPermissionOperationAndTypedResource() {
        record InvoiceId(UUID value) {}
        record OtherId(UUID value) {}
        var id = new InvoiceId(UUID.randomUUID());
        var resource = ResourceReference.of(InvoiceId.class, id);
        assertNotEquals(resource, ResourceReference.of(OtherId.class, new OtherId(id.value())));
        inTenant(() -> {
            try (var outer = guard.enter("example.Outer", Mode.PUBLIC)) {
                try (var inner = guard.enter("example.Inner", permission(view, resource))) {
                    assertEquals(human, AuthorizationGuard.currentActor().orElseThrow());
                }
                assertEquals(human, AuthorizationGuard.currentActor().orElseThrow());
                decision = PermissionDecision.DENY;
                code(ACCESS_DENIED, () -> guard.enter("example.Inner", permission(view, resource)));
            }
            return null;
        });
        assertEquals(2, calls.size());
        assertEquals("example.Inner", calls.getFirst().operation()); assertSame(id, calls.getFirst().resource().value());
        assertEquals(tenant, calls.getFirst().tenant());
    }
    @Test void allAnyPreserveOrderAndDoNotEvaluateSkippedBranches() {
        var evaluated = new ArrayList<String>();
        var policy = new AuthorizationGuard(() -> Optional.of(human), r -> {
            evaluated.add(r.permission().qualifiedName());
            return r.permission().qualifiedName().equals(view.qualifiedName()) ? PermissionDecision.ALLOW : PermissionDecision.DENY;
        });
        inTenant(() -> {
            try (var scope = policy.enter("example.Read", all(permission(view), any(permission(edit), permission(view))))) {}
            assertEquals(List.of(view.qualifiedName(), edit.qualifiedName(), view.qualifiedName()), evaluated);
            evaluated.clear();
            try (var scope = policy.enter("example.Read", any(permission(view), permission(edit)))) {}
            assertEquals(List.of(view.qualifiedName()), evaluated); evaluated.clear();
            code(ACCESS_DENIED, () -> policy.enter("example.Read", all(permission(edit), permission(view))));
            assertEquals(List.of(edit.qualifiedName()), evaluated); return null;
        });
    }
    @Test void backendFailuresAndAbstentionAreTechnicalNotAlternativeDenials() {
        for (RuntimeException failed : List.of(new IllegalStateException("seeded-secret"), new AuthorizationException(ACCESS_DENIED),
                new AuthorizationException(AUTHORIZATION_UNAVAILABLE))) {
            var policy = new AuthorizationGuard(() -> Optional.of(human), request -> { throw failed; });
            inTenant(() -> {
                var error = assertThrows(AuthorizationException.class,
                        () -> policy.enter("example.Export", any(permission(view), permission(edit))));
                assertEquals(failed instanceof AuthorizationException a && a.code() == AUTHORIZATION_UNAVAILABLE
                        ? AUTHORIZATION_UNAVAILABLE : AUTHORIZATION_CONFIGURATION_ERROR, error.code());
                assertNull(error.getCause()); assertFalse(error.toString().contains("seeded")); return null;
            });
        }
        var abstain = new AuthorizationGuard(() -> Optional.of(human), request -> null);
        inTenant(() -> { code(AUTHORIZATION_CONFIGURATION_ERROR, () -> abstain.enter("example.Export", permission(view))); return null; });
        var brokenActor = new AuthorizationGuard(() -> { throw new IllegalStateException("seeded-secret"); }, r -> PermissionDecision.ALLOW);
        transaction.executeWithoutResult(s -> code(AUTHORIZATION_CONFIGURATION_ERROR, () -> brokenActor.enter("example.Export", Mode.AUTHENTICATED)));
    }
    @Test void nestedActorReplacementIsRejectedWithoutDamagingOuterFrame() {
        inTenant(() -> {
            try (var scope = guard.enter("example.Outer", Mode.AUTHENTICATED)) {
                actor = new VerifiedActor("different-realm", human.subject(), VerifiedActor.Kind.HUMAN);
                code(AUTHORIZATION_CONFIGURATION_ERROR, () -> guard.enter("example.Inner", Mode.AUTHENTICATED));
                assertEquals(human, AuthorizationGuard.currentActor().orElseThrow()); actor = human;
                assertThrows(TenancyException.class, () -> TenantContext.openVerified(TenantId.of(UUID.randomUUID())));
            }
            return null;
        });
    }
    @Test void framesAreThreadOwnedLifoAndCleanedAfterErrors() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (var outer = guard.enter("example.Outer", Mode.PUBLIC)) {
                var inner = guard.enter("example.Inner", Mode.PUBLIC);
                code(AUTHORIZATION_CONFIGURATION_ERROR, outer::close);
                executor.submit(() -> {
                    assertTrue(AuthorizationGuard.currentActor().isEmpty());
                    code(AUTHORIZATION_CONFIGURATION_ERROR, inner::close);
                }).get();
                inner.close(); inner.close();
            }
            executor.submit(() -> {
                assertThrows(IllegalStateException.class, () -> {
                    try (var scope = guard.enter("example.Job", Mode.PUBLIC)) { throw new IllegalStateException("body"); }
                });
                assertTrue(AuthorizationGuard.currentActor().isEmpty());
            }).get();
            executor.submit(() -> assertTrue(AuthorizationGuard.currentActor().isEmpty())).get();
        }
    }
    @Test void requirementsAreImmutableQualifiedAndRejectInvalidGroups() {
        assertThrows(IllegalArgumentException.class, () -> all());
        assertThrows(IllegalArgumentException.class, () -> any(Mode.PUBLIC));
        assertThrows(IllegalArgumentException.class, () -> all(Mode.AUTHENTICATED));
        assertThrows(IllegalArgumentException.class, () -> all(permission(view), permission(PermissionKey.of(view.qualifiedName()))));
        assertThrows(IllegalArgumentException.class, () -> PermissionKey.of("VIEW"));
        var source = new ArrayList<AuthorizationRequirement>(); source.add(permission(view));
        var requirement = new All(source); source.clear(); assertEquals(1, requirement.children().size());
        assertThrows(UnsupportedOperationException.class, () -> requirement.children().clear());
        assertThrows(NullPointerException.class, () -> permission(view, null));
    }
    @Test void metadataAndFailuresDoNotImplicitlyExposeSensitiveValues() {
        var resource = ResourceReference.of(String.class, "seeded-resource");
        for (Object object : List.of(human, tenant, resource, permission(view, resource),
                new PermissionRequest("example.Export", human, tenant, view, resource))) {
            assertFalse(object.toString().contains("seeded"));
        }
        var failure = new AuthorizationException(AUTHORIZATION_UNAVAILABLE);
        assertThrows(IllegalStateException.class, () -> failure.initCause(new IllegalStateException("seeded-secret")));
        failure.addSuppressed(new IllegalStateException("seeded-secret")); assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure instanceof RuntimeException && org.vernac.runtime.VernacDomainException.class.isInstance(failure));
    }
    private static final class Manager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
