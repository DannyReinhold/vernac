// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.vernac.runtime.authorization.spring.*;

class SpringAuthorizationTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void missingAnonymousAndUnverifiedTokensNeverReachTheTrustedMapper() {
        var calls = new AtomicInteger();
        var actors = new SpringSecurityActorResolver(auth -> { calls.incrementAndGet(); throw new AssertionError("mapper"); });
        assertTrue(actors.resolve().isEmpty());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("fixture", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertTrue(actors.resolve().isEmpty());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated("seeded-name", "seeded-token"));
        assertTrue(actors.resolve().isEmpty()); assertEquals(0, calls.get());
    }
    @Test void disabledLockedExpiredAndExpiredCredentialsAreNotEnabledActors() {
        var calls = new AtomicInteger();
        var resolver = new SpringSecurityActorResolver(auth -> { calls.incrementAndGet(); throw new AssertionError("mapper"); });
        for (int state = 0; state < 4; state++) {
            var user = new User("seeded-name", "seeded-password", state != 0, state != 1, state != 2, state != 3, List.of());
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
            assertTrue(resolver.resolve().isEmpty());
        }
        assertEquals(0, calls.get());
    }
    @Test void humanAndTechnicalPrincipalMappingAreExplicitAndDoNotInferPrivileges() {
        var subject = new VerifiedActor("trusted-issuer", "seeded-id", VerifiedActor.Kind.HUMAN);
        var resolver = new SpringSecurityActorResolver(auth -> Optional.of(subject));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("seeded-display", "seeded-token", List.of()));
        assertSame(subject, resolver.resolve().orElseThrow());
        var worker = new VerifiedActor("trusted-jobs", "seeded-worker", VerifiedActor.Kind.TECHNICAL);
        assertSame(worker, new SpringSecurityActorResolver(auth -> Optional.of(worker)).resolve().orElseThrow());
        assertTrue(new SpringSecurityActorResolver(auth -> Optional.empty()).resolve().isEmpty());
    }
    @Test void explicitConfigurationRequiresExactlyOneActorAndPolicyBinding() {
        for (int[] counts : List.of(new int[]{0, 0}, new int[]{1, 0}, new int[]{0, 1}, new int[]{2, 1}, new int[]{1, 2})) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(VernacAuthorizationConfiguration.class);
                for (int i = 0; i < counts[0]; i++) context.registerBean("actor" + i, ActorResolver.class, () -> Optional::empty);
                for (int i = 0; i < counts[1]; i++) context.registerBean("policy" + i, PermissionEvaluator.class, () -> request -> PermissionDecision.DENY);
                var failed = assertThrows(org.springframework.beans.BeansException.class, context::refresh);
                Throwable cause = failed;
                while (cause.getCause() != null) cause = cause.getCause();
                assertEquals(AuthorizationException.class, cause.getClass());
                assertEquals(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR, ((AuthorizationException) cause).code());
            }
        }
    }
    @Test void registrationIsOptInAndAddsNoSecurityFilterOrPermissionInterceptor() {
        try (var plain = new AnnotationConfigApplicationContext()) {
            plain.refresh(); assertTrue(plain.getBeansOfType(AuthorizationGuard.class).isEmpty());
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(VernacAuthorizationConfiguration.class);
            context.registerBean(ActorResolver.class, () -> Optional::empty);
            context.registerBean(PermissionEvaluator.class, () -> request -> PermissionDecision.DENY);
            context.refresh(); assertEquals(1, context.getBeansOfType(AuthorizationGuard.class).size());
            assertFalse(java.util.Arrays.stream(context.getBeanDefinitionNames()).anyMatch(n -> n.contains("FilterChain") || n.contains("Interceptor")));
        }
    }
}
