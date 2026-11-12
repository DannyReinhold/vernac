// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.vernac.experiments.tenancy.PostgresFixture.*;
import static org.vernac.experiments.tenancy.AuthorizationEntryProbe.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class AuthorizationEntryPostgresTest {
    PostgresFixture fixture;
    AnnotationConfigApplicationContext context;
    TenantBoundary boundary;
    InvoiceStore store;
    Trace trace;
    Policy policy;
    Policy policyTarget;
    Guard guard;
    Inner inner;
    Outer outer;
    TransactionTemplate transaction;
    static final InvoiceId ID = new InvoiceId(INVOICE_A);

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}
    @BeforeAll void database() throws Exception { fixture = new PostgresFixture(); }
    @AfterAll void closeDatabase() throws Exception { if (fixture != null) fixture.close(); }
    @BeforeEach void setup() throws Exception {
        fixture.reset();
        boundary = new TenantBoundary(fixture.pool, (actor, tenant) -> actor.equals("member") && (tenant.equals(A) || tenant.equals(B)));
        store = new InvoiceStore(new JdbcTemplate(fixture.pool), boundary);
        trace = new Trace();
        policyTarget = new Policy(store, trace);
        var manager = new DataSourceTransactionManager(fixture.pool);
        transaction = new TransactionTemplate(manager);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(Trace.class, () -> trace);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
        context.registerBean(Policy.class, () -> policyTarget);
        context.registerBean(Guard.class, () -> new Guard(AuthorizationEntryPostgresTest::actor, context.getBean(Policy.class)));
        context.registerBean(Inner.class, () -> new Inner(context.getBean(Guard.class), store, trace));
        context.registerBean(Outer.class, () -> new Outer(context.getBean(Guard.class), context.getBean(Inner.class), store));
        context.register(Transactions.class); context.refresh();
        policy = context.getBean(Policy.class); guard = context.getBean(Guard.class);
        inner = context.getBean(Inner.class); outer = context.getBean(Outer.class);
        authenticate("member");
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext(); context.close();
        assertFalse(Guard.contextPresent());
        assertTrue(TenantContext.currentTenant().isEmpty());
        assertTrue(TenantContext.transactionTenant().isEmpty());
    }
    static Actor actor(org.springframework.security.core.Authentication authentication) {
        return new Actor(authentication.getName(), authentication.getName().equals("worker") ? Actor.Kind.TECHNICAL : Actor.Kind.HUMAN);
    }
    static void authenticate(String subject) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(subject, "seeded-credential", List.of()));
    }
    <T> T scoped(Supplier<T> work) { try (var ignored = boundary.select(A)) { return work.get(); } }
    <T> T evaluated(Requirement requirement, Supplier<T> work) {
        return scoped(() -> transaction.execute(status -> guard.invoke("example.Probe", requirement, work)));
    }
    static void code(Code expected, org.junit.jupiter.api.function.Executable work) {
        var failure = assertThrows(Failure.class, work);
        assertEquals(expected, failure.code); assertEquals(expected.name(), failure.getMessage());
        assertNull(failure.getCause(), "Raw infrastructure causes must not escape the public failure");
    }
    String title() { return new JdbcTemplate(fixture.adminSource()).queryForObject("SELECT title FROM tenant_spike.invoice WHERE id=?", String.class, INVOICE_A); }

    @Test void guardOrdersNullPermissionValidationBodyAndResult() {
        code(Code.ACCESS_DENIED, () -> scoped(() -> inner.execute(ID)));
        assertEquals(List.of("policy:EXPORT"), trace.events); assertEquals("A title", title());
        trace.events.clear();
        assertThrows(DomainValidationException.class, () -> inner.execute(null));
        assertTrue(trace.events.isEmpty());
        policyTarget.decisions.put(Permission.EXPORT, true);
        assertEquals("done", scoped(() -> inner.execute(ID)));
        assertEquals(List.of("policy:EXPORT", "validates", "body", "result"), trace.events);
        assertEquals("exported", title());
    }

    @Test void nestedChecksRunOnlyWhenReachedAndRequiredFailureRollsBackAllWrites() {
        assertEquals("outer", scoped(() -> outer.execute(false, false, ID)));
        assertTrue(trace.calls.isEmpty());
        code(Code.ACCESS_DENIED, () -> scoped(() -> outer.execute(true, false, ID)));
        assertEquals("outer write", title());
        assertEquals(2L, new JdbcTemplate(fixture.adminSource()).queryForObject("SELECT version FROM tenant_spike.invoice WHERE id=?", Long.class, INVOICE_A));
        assertThrows(UnexpectedRollbackException.class, () -> scoped(() -> outer.execute(true, true, ID)));
        assertEquals("outer write", title());
        assertEquals(2L, new JdbcTemplate(fixture.adminSource()).queryForObject("SELECT version FROM tenant_spike.invoice WHERE id=?", Long.class, INVOICE_A));
        policyTarget.decisions.put(Permission.EXPORT, true);
        assertEquals("done", scoped(() -> outer.execute(true, false, ID)));
        assertEquals("example.ExportInvoice", trace.calls.getLast().operation());
        assertEquals(TenantId.of(A), trace.calls.getLast().tenant());
        assertEquals("member", trace.calls.getLast().actor().subject());
    }

    @Test void allAnyShortCircuitAndTechnicalFailureNeverBecomesAnotherAlternative() {
        var view = new Leaf(Permission.VIEW, ID); var export = new Leaf(Permission.EXPORT, ID);
        policyTarget.decisions.put(Permission.VIEW, true);
        assertEquals("ok", evaluated(new Any(List.of(view, export)), () -> "ok"));
        assertEquals(List.of("policy:VIEW"), trace.events);
        trace.events.clear();
        code(Code.ACCESS_DENIED, () -> evaluated(new All(List.of(export, view)), () -> fail("body")));
        assertEquals(List.of("policy:EXPORT"), trace.events);
        trace.events.clear(); policyTarget.broken = Permission.EXPORT; policyTarget.unavailable = true;
        code(Code.AUTHORIZATION_UNAVAILABLE, () -> evaluated(new Any(List.of(export, view)), () -> fail("body")));
        assertEquals(List.of("policy:EXPORT"), trace.events);
        policyTarget.unavailable = false;
        code(Code.AUTHORIZATION_CONFIGURATION_ERROR, () -> evaluated(export, () -> fail("body")));
        code(Code.AUTHORIZATION_CONFIGURATION_ERROR, () -> evaluated(new All(List.of()), () -> fail("body")));
        var abstaining = new Guard(AuthorizationEntryPostgresTest::actor, (auth, call) -> null);
        code(Code.AUTHORIZATION_CONFIGURATION_ERROR, () -> scoped(() -> transaction.execute(s -> abstaining.invoke("x", view, () -> fail("body")))));
    }

    @Test void absentForeignAndWrongNominalResourcesDenyWithoutDisclosingExistence() {
        policyTarget.decisions.put(Permission.EXPORT, true);
        for (Object resource : List.of(new InvoiceId(INVOICE_B), new InvoiceId(UUID.randomUUID()), new OtherId(INVOICE_A))) {
            code(Code.ACCESS_DENIED, () -> evaluated(new Leaf(Permission.EXPORT, resource), () -> fail("body")));
        }
        assertEquals("A title", title());
        code(Code.SCOPE_REQUIRED, () -> transaction.execute(s -> guard.invoke("x", new Leaf(Permission.EXPORT, ID), () -> fail("body"))));
    }

    @Test void directEntryMissingInfrastructureAndAnonymousNeverReachPolicyOrBody() {
        code(Code.AUTHORIZATION_CONFIGURATION_ERROR, () -> new Inner(guard, store, trace).execute(ID));
        assertTrue(trace.events.isEmpty());
        var missing = new Guard(AuthorizationEntryPostgresTest::actor, null);
        code(Code.AUTHORIZATION_CONFIGURATION_ERROR, () -> scoped(() -> transaction.execute(s -> missing.invoke("x", new Leaf(Permission.VIEW, ID), () -> fail("body")))));
        SecurityContextHolder.clearContext();
        code(Code.AUTHENTICATION_REQUIRED, () -> transaction.execute(s -> guard.invoke("x", Mode.AUTHENTICATED, () -> fail("body"))));
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("fixture", "anonymous", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ANONYMOUS"))));
        code(Code.AUTHENTICATION_REQUIRED, () -> transaction.execute(s -> guard.invoke("x", Mode.AUTHENTICATED, () -> fail("body"))));
        assertEquals("public", guard.invoke("x", Mode.PUBLIC, () -> "public"));
        assertTrue(trace.events.isEmpty());
    }

    @Test void nestedActorOrTenantReplacementFailsAndTechnicalActorsAreExplicit() {
        evaluated(Mode.AUTHENTICATED, () -> {
            authenticate("worker");
            try { code(Code.AUTHORIZATION_CONFIGURATION_ERROR, () -> guard.invoke("inner", Mode.AUTHENTICATED, () -> fail("body"))); }
            finally { authenticate("member"); }
            assertThrows(org.vernac.runtime.tenancy.TenancyException.class, () -> TenantContext.openVerified(TenantId.of(B)));
            return "ok";
        });
        authenticate("worker");
        assertEquals("technical", transaction.execute(s -> guard.invoke("worker", Mode.AUTHENTICATED, () -> "technical")));
        assertFalse(Guard.contextPresent());
    }

    @Test void reusedExecutorClearsInvocationTenantAndAuthenticationAfterFailure() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                authenticate("member");
                try { code(Code.ACCESS_DENIED, () -> scoped(() -> inner.execute(ID))); }
                finally { SecurityContextHolder.clearContext(); }
                assertFalse(Guard.contextPresent()); assertTrue(TenantContext.currentTenant().isEmpty());
                assertTrue(TenantContext.transactionTenant().isEmpty());
            }).get();
            executor.submit(() -> {
                assertNull(SecurityContextHolder.getContext().getAuthentication()); assertFalse(Guard.contextPresent());
                code(Code.AUTHENTICATION_REQUIRED, () -> transaction.execute(s -> guard.invoke("x", Mode.AUTHENTICATED, () -> fail("body"))));
                SecurityContextHolder.clearContext();
            }).get();
        }
    }
    private AnnotationConfigWebApplicationContext webContext() {
        var web = new AnnotationConfigWebApplicationContext();
        web.setServletContext(new MockServletContext());
        web.setParent(context); web.register(AuthorizationWebProbe.class); web.refresh(); return web;
    }
    private MockMvc mvc(WebApplicationContext web) {
        return MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }
    @Test void restAndMvcUseTerminalSafeFailuresBeforeGenericErrorHooks() throws Exception {
        try (var web = webContext()) {
            var mvc = mvc(web);
            // The same classifier must win over generic hooks for API and MVC failures.
            var api = mvc.perform(get("/api")
                    .with(user("member"))).andReturn();
            assertEquals(403, api.getResponse().getStatus());
            assertTrue(api.getResponse().getContentAsString().contains("ACCESS_DENIED"));
            trace.events.clear();
            var denied = mvc.perform(get("/mvc")
                    .with(user("member"))).andReturn();
            assertEquals(403, denied.getResponse().getStatus());
            assertEquals("access-denied", denied.getModelAndView().getViewName());
            assertEquals("ACCESS_DENIED", denied.getModelAndView().getModel().get("code"));
            assertEquals(List.of("policy:EXPORT"), trace.events); assertEquals(0, trace.genericErrorHooks);
            policyTarget.broken = Permission.EXPORT; policyTarget.unavailable = true;
            var unavailable = mvc.perform(get("/mvc")
                    .with(user("member"))).andReturn();
            assertEquals(503, unavailable.getResponse().getStatus());
            assertEquals("AUTHORIZATION_UNAVAILABLE", unavailable.getModelAndView().getModel().get("code"));
            var remote = mvc.perform(get("/api")
                    .with(user("member"))).andReturn();
            assertEquals(503, remote.getResponse().getStatus());
            assertTrue(remote.getResponse().getContentAsString().contains("AUTHORIZATION_UNAVAILABLE"));
            policyTarget.unavailable = false;
            var bug = mvc.perform(get("/api")
                    .with(user("member"))).andReturn();
            assertEquals(500, bug.getResponse().getStatus());
            assertTrue(bug.getResponse().getContentAsString().contains("AUTHORIZATION_CONFIGURATION_ERROR"));
            assertFalse(bug.getResponse().getContentAsString().contains("seeded"));
            assertEquals(0, trace.genericErrorHooks); assertEquals("A title", title());
        }
    }
    @Test void applicationFilterChainKeepsLoginAndCsrfWithoutDuplicatingPermissionChecks() throws Exception {
        try (var web = webContext()) {
            var mvc = mvc(web);
            var login = mvc.perform(get("/private")
                    .with(anonymous())).andReturn();
            assertEquals(401, login.getResponse().getStatus());
            assertTrue(login.getResponse().getContentAsString().contains("AUTHENTICATION_REQUIRED"));
            var anonymous = mvc.perform(get("/api")
                    .with(anonymous())).andReturn();
            assertEquals(401, anonymous.getResponse().getStatus());
            assertTrue(anonymous.getResponse().getContentAsString().contains("AUTHENTICATION_REQUIRED"));
            assertEquals(0, trace.genericErrorHooks);

            var csrf = mvc.perform(post("/api")
                    .with(user("member"))).andReturn();
            assertEquals(403, csrf.getResponse().getStatus()); assertTrue(trace.events.isEmpty());
            policyTarget.decisions.put(Permission.EXPORT, true);
            var success = mvc.perform(post("/api")
                    .with(user("member"))
                    .with(csrf())).andReturn();
            assertEquals(200, success.getResponse().getStatus()); assertEquals("done", success.getResponse().getContentAsString());
            assertEquals(List.of("policy:EXPORT", "validates", "body", "result"), trace.events);
            assertFalse(Guard.contextPresent()); assertTrue(TenantContext.currentTenant().isEmpty());
        }
    }

}
