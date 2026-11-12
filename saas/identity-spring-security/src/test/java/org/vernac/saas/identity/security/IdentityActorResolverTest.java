// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.saas.identity.domain.*;

class IdentityActorResolverTest {
    private final UserId id=UserId.create();
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @Test void activeNominalIdentityMapsToAHumanWithoutRolesOrTenantSelection() {
        authenticate(new IdentityPrincipal(id));var calls=new AtomicInteger();
        var resolver=new IdentityActorResolver(user->{assertEquals(id,user);calls.incrementAndGet();return Optional.of(IdentityStatus.ACTIVE);});
        var actor=resolver.resolve().orElseThrow();
        assertEquals(new VerifiedActor(IdentityActorResolver.REALM,id.asString(),VerifiedActor.Kind.HUMAN),actor);
        assertEquals(1,calls.get());assertTrue(TenantContext.currentTenant().isEmpty());
        assertFalse(actor.toString().contains(id.asString()));
    }
    @Test void absentAnonymousUnauthenticatedAndForeignPrincipalsNeverQueryTheDirectory() {
        var resolver=new IdentityActorResolver(user->{fail("Untrusted principal reached identity storage");return Optional.empty();});
        assertTrue(resolver.resolve().isEmpty());
        authenticate("admin");assertTrue(resolver.resolve().isEmpty());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated(new IdentityPrincipal(id),null));
        assertTrue(resolver.resolve().isEmpty());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("anonymous",new IdentityPrincipal(id),List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertTrue(resolver.resolve().isEmpty());
    }
    @Test void everyNonActiveOrMissingIdentityDeniesEvenWithAdministratorAuthorities() {
        authenticate(new IdentityPrincipal(id));
        for(var status:IdentityStatus.values())if(status!=IdentityStatus.ACTIVE)
            assertTrue(new IdentityActorResolver(user->Optional.of(status)).resolve().isEmpty(),status.name());
        assertTrue(new IdentityActorResolver(user->Optional.empty()).resolve().isEmpty());
    }
    @Test void restoredPrincipalsDoNotCacheEnabledStateAcrossResolutions() {
        authenticate(new IdentityPrincipal(id));var state=new AtomicReference<>(IdentityStatus.ACTIVE);var calls=new AtomicInteger();
        var resolver=new IdentityActorResolver(user->{calls.incrementAndGet();return Optional.of(state.get());});
        assertTrue(resolver.resolve().isPresent());state.set(IdentityStatus.DISABLED);assertTrue(resolver.resolve().isEmpty());
        state.set(IdentityStatus.ACTIVE);assertTrue(resolver.resolve().isPresent());assertEquals(3,calls.get());
    }
    private static class Transactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition){}
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){}
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){}
    }
    @Test void generatedGuardContractUsesTheLiveActorAndRetainsSafeDenialSemantics() {
        authenticate(new IdentityPrincipal(id));var state=new AtomicReference<>(IdentityStatus.ACTIVE);
        var resolver=new IdentityActorResolver(user->Optional.of(state.get()));
        var guard=new AuthorizationGuard(resolver,request->{fail("Authenticated mode must not grant/evaluate a permission");return PermissionDecision.DENY;});
        var transactions=new org.springframework.transaction.support.TransactionTemplate(new Transactions());
        transactions.execute(status->{try(var call=guard.enter("example.Read",AuthorizationRequirement.Mode.AUTHENTICATED)) {
            assertEquals(id.asString(),AuthorizationGuard.currentActor().orElseThrow().subject());
        }return null;});
        assertTrue(AuthorizationGuard.currentActor().isEmpty());state.set(IdentityStatus.DISABLED);
        var denied=assertThrows(AuthorizationException.class,()->transactions.execute(status->{try(var call=guard.enter("example.Read",AuthorizationRequirement.Mode.AUTHENTICATED)){fail("Disabled identity entered the body");}return null;}));
        assertEquals(AuthorizationException.Code.AUTHENTICATION_REQUIRED,denied.code());assertTrue(AuthorizationGuard.currentActor().isEmpty());
    }
    @Test void eligibilityChangeInsideActiveInvocationAbortsThroughTheExistingContextPin() {
        authenticate(new IdentityPrincipal(id));var state=new AtomicReference<>(IdentityStatus.ACTIVE);
        var guard=new AuthorizationGuard(new IdentityActorResolver(user->Optional.of(state.get())),request->PermissionDecision.DENY);
        var transactions=new org.springframework.transaction.support.TransactionTemplate(new Transactions());
        var changed=assertThrows(AuthorizationException.class,()->transactions.execute(status->{
            try(var outer=guard.enter("example.Outer",AuthorizationRequirement.Mode.AUTHENTICATED)) {
                state.set(IdentityStatus.DISABLED);
                try(var inner=guard.enter("example.Inner",AuthorizationRequirement.Mode.AUTHENTICATED)){fail("Changed eligibility entered a nested body");}
            }return null;
        }));
        assertEquals(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR,changed.code());
        assertTrue(AuthorizationGuard.currentActor().isEmpty());
    }
    @Test void sharedResolverDoesNotCacheSubjectsAcrossConcurrentSecurityContexts() throws Exception {
        var other=UserId.create();var users=Set.of(id,other);var rendezvous=new java.util.concurrent.CyclicBarrier(2);
        var resolver=new IdentityActorResolver(user->{assertTrue(users.contains(user));try{rendezvous.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failed){throw new IllegalStateException(failed);}return Optional.of(IdentityStatus.ACTIVE);});
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=users.stream().<java.util.concurrent.Future<String>>map(user->executor.submit(()->{
                try{authenticate(new IdentityPrincipal(user));return resolver.resolve().orElseThrow().subject();}
                finally{SecurityContextHolder.clearContext();}
            })).toList();
            var subjects=new HashSet<String>();for(var task:tasks)subjects.add(task.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(Set.of(id.asString(),other.asString()),subjects);
            assertTrue(executor.submit(()->SecurityContextHolder.getContext().getAuthentication()==null&&resolver.resolve().isEmpty()).get(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void miswiredRecursiveDirectoryFailsClearlyAndCleansItsResolutionFrame() {
        authenticate(new IdentityPrincipal(id));var recursive=new AtomicBoolean(true);var reference=new AtomicReference<IdentityActorResolver>();
        var resolver=new IdentityActorResolver(user->{if(recursive.get())reference.get().resolve();return Optional.of(IdentityStatus.ACTIVE);});reference.set(resolver);
        var failure=assertThrows(AuthorizationException.class,resolver::resolve);
        assertEquals(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR,failure.code());assertNull(failure.getCause());
        recursive.set(false);assertTrue(resolver.resolve().isPresent());
    }
    @Test void storageFailuresAndMalformedResultsAreDistinctAndDoNotRetainSecrets() {
        authenticate(new IdentityPrincipal(id));String secret="jdbc:secret/password/"+id.asString();
        var failed=assertThrows(AuthorizationException.class,()->new IdentityActorResolver(user->{throw new IllegalStateException(secret);}).resolve());
        assertEquals(AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE,failed.code());assertNull(failed.getCause());
        assertFalse(failed.toString().contains(secret));assertFalse(failed.toString().contains(id.asString()));
        var malformed=assertThrows(AuthorizationException.class,()->new IdentityActorResolver(user->null).resolve());
        assertEquals(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR,malformed.code());assertNull(malformed.getCause());
    }
}
