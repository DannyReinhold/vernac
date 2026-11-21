// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.experiments.tenancy.PostgresFixture.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.tenancy.TenantId;
import org.vernac.runtime.tenancy.TenancyException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class TenantIsolationPostgresTest {
    PostgresFixture fixture;
    JdbcTemplate jdbc;
    TenantBoundary boundary;
    InvoiceStore store;
    DataSourceTransactionManager manager;
    TransactionTemplate required;
    AnnotationConfigApplicationContext context;
    ManagedUsecases.Trace trace;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @BeforeAll void database() throws Exception { fixture=new PostgresFixture(); }
    @AfterAll void cleanupDatabase() throws Exception { if(fixture!=null) fixture.close(); }

    @BeforeEach void setup() throws Exception {
        fixture.reset();
        jdbc=new JdbcTemplate(fixture.pool);
        boundary=new TenantBoundary(fixture.pool, (actor, tenant) -> actor.equals("member") && (tenant.equals(A)||tenant.equals(B)));
        store=new InvoiceStore(jdbc,boundary);
        manager=new DataSourceTransactionManager(fixture.pool); required=new TransactionTemplate(manager);
        trace=new ManagedUsecases.Trace();
        context=new AnnotationConfigApplicationContext();
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
        context.registerBean(ManagedUsecases.Policy.class, () -> new ManagedUsecases.Policy(store,trace));
        context.registerBean(ManagedUsecases.Inner.class, () -> new ManagedUsecases.Inner(boundary,store,context.getBean(ManagedUsecases.Policy.class),trace));
        context.registerBean(ManagedUsecases.Outer.class, () -> new ManagedUsecases.Outer(boundary,store,context.getBean(ManagedUsecases.Inner.class),trace));
        context.register(Transactions.class); context.refresh();
        authenticate(true);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if(context!=null) context.close();
        assertFalse(boundary.isSelected(), "Execution selection leaked");
        assertFalse(boundary.isBound(), "Transaction resource leaked");
        String setting=jdbc.queryForObject("SELECT current_setting('vernac.tenant_id', true)", String.class);
        assertTrue(setting==null||setting.isEmpty(), "Pooled session retained tenant state");
    }

    private static void authenticate(boolean allowed) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "member", null, allowed?List.of(new SimpleGrantedAuthority("INVOICE_EXPORT")):List.of()));
    }
    private <T> T inTenant(UUID tenant, Supplier<T> action) {
        try(var ignored=boundary.select(tenant)) { return required.execute(status -> action.get()); }
    }
    private static void code(String code, Runnable action) {
        var error = assertThrows(RuntimeException.class, action::run);
        assertTrue(error instanceof TenantBoundary.Failure || error instanceof TenancyException);
        String expected = code.equals("TRANSACTION_REQUIRED") && error instanceof TenancyException
                ? "TENANT_TRANSACTION_REQUIRED" : code;
        assertEquals(expected, error.getMessage());
    }

    @Test void missingAuthenticationMembershipAndTransactionFailClosed() {
        SecurityContextHolder.clearContext();
        code("AUTHENTICATION_REQUIRED", () -> boundary.select(A));
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymous", List.of(new SimpleGrantedAuthority("ANONYMOUS"))));
        code("AUTHENTICATION_REQUIRED", () -> boundary.select(A));
        authenticate(true);
        code("ACCESS_DENIED", () -> boundary.select(UUID.randomUUID()));
        code("TRANSACTION_REQUIRED", store::visibleCount);
        code("TRANSACTION_REQUIRED", () -> new ManagedUsecases.Inner(boundary, store,
                context.getBean(ManagedUsecases.Policy.class), trace).execute(INVOICE_A));
        try (var selection = boundary.select(A)) {
            var wrongManager = new TransactionTemplate(new DataSourceTransactionManager(fixture.ownerSource()));
            code("TRANSACTION_REQUIRED", () -> wrongManager.execute(status -> boundary.requireBound()));
        }
        code("TENANT_CONTEXT_REQUIRED", () -> required.execute(status -> store.visibleCount()));
    }

    @Test void byIdFindOrCountAndRawSqlAreTenantScoped() {
        assertEquals(1L, inTenant(A, store::visibleCount));
        assertEquals(List.of(INVOICE_A), inTenant(A, () -> store.find("A title","B title")));
        assertEquals("A title", inTenant(A, () -> store.byId(INVOICE_A).title));
        code("NOT_FOUND", () -> inTenant(A, () -> store.byId(INVOICE_B)));
        code("NOT_FOUND", () -> inTenant(A, () -> store.byId(UUID.randomUUID())));
        assertEquals(List.of(INVOICE_A), inTenant(A, () -> { boundary.requireBound();
            return jdbc.query("SELECT id FROM tenant_spike.invoice", (r,n)->r.getObject(1,UUID.class)); }));
        assertEquals(1L, inTenant(B, store::visibleCount));
    }

    @Test void unmarkedGlobalDataNeedsNoTenantAndDoesNotBindOne() {
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM tenant_spike.catalog", Long.class));
        assertFalse(boundary.isBound());
        assertEquals(1L, inTenant(A, () -> jdbc.queryForObject("SELECT count(*) FROM tenant_spike.catalog", Long.class)));
        assertFalse(boundary.isBound());
        // With no setting, RLS matches no tenant rows even for raw runtime SQL.
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tenant_spike.invoice", Long.class));
    }

    @Test void runtimeRoleAndForcedOwnerProtectionAreReal() {
        boundary.verifyRuntimeRole();
        assertEquals(0L,new JdbcTemplate(fixture.ownerSource()).queryForObject("SELECT count(*) FROM tenant_spike.invoice",Long.class));
        code("TENANT_RUNTIME_ROLE_INVALID",()->new TenantBoundary(fixture.ownerSource(),(a,t)->true).verifyRuntimeRole());
        code("TENANT_RUNTIME_ROLE_INVALID",()->new TenantBoundary(fixture.adminSource(),(a,t)->true).verifyRuntimeRole());
        fixture.admin("ALTER ROLE "+fixture.runtime+" BYPASSRLS");
        try {code("TENANT_RUNTIME_ROLE_INVALID",boundary::verifyRuntimeRole);}
        finally {fixture.admin("ALTER ROLE "+fixture.runtime+" NOBYPASSRLS");}
    }

    @Test void coordinatorRejectsIncompleteConfigurationAndUnsafeChildTables() {
        assertThrows(IllegalArgumentException.class, () ->
                new org.vernac.runtime.jdbc.tenancy.JdbcTenantCoordinator(fixture.pool, java.util.List.of()));
        var missing = new org.vernac.runtime.jdbc.tenancy.JdbcTenantCoordinator(
                fixture.pool, java.util.List.of("tenant_spike.missing"));
        code("TENANT_CONTEXT_CONFIGURATION_ERROR", missing::verifyRuntimeRole);
        fixture.admin("ALTER TABLE tenant_spike.line NO FORCE ROW LEVEL SECURITY");
        try { code("TENANT_RUNTIME_ROLE_INVALID", boundary::verifyRuntimeRole); }
        finally { fixture.admin("ALTER TABLE tenant_spike.line FORCE ROW LEVEL SECURITY"); }
        fixture.admin("ALTER TABLE tenant_spike.line DISABLE ROW LEVEL SECURITY");
        try { code("TENANT_RUNTIME_ROLE_INVALID", boundary::verifyRuntimeRole); }
        finally { fixture.admin("ALTER TABLE tenant_spike.line ENABLE ROW LEVEL SECURITY"); }
        assertFalse(boundary.isBound());
    }

    @Test void coordinatorRejectsSchemaCreationAndTruncatePrivileges() {
        fixture.admin("GRANT CREATE ON SCHEMA tenant_spike TO " + fixture.runtime);
        try { code("TENANT_RUNTIME_ROLE_INVALID", boundary::verifyRuntimeRole); }
        finally { fixture.admin("REVOKE CREATE ON SCHEMA tenant_spike FROM " + fixture.runtime); }
        fixture.admin("GRANT TRUNCATE ON tenant_spike.line TO " + fixture.runtime);
        try { code("TENANT_RUNTIME_ROLE_INVALID", boundary::verifyRuntimeRole); }
        finally { fixture.admin("REVOKE TRUNCATE ON tenant_spike.line FROM " + fixture.runtime); }
        boundary.verifyRuntimeRole();
    }

    @Test void rowSecurityBlocksCrossTenantWritesAndForeignKeysBlockCrossTenantLinks() {
        assertThrows(org.springframework.dao.DataAccessException.class, () -> inTenant(A, () -> {
            boundary.requireBound(); return jdbc.update("INSERT INTO tenant_spike.invoice VALUES (?,?,?,1)",UUID.randomUUID(),B,"denied"); }));
        assertEquals(0, inTenant(A, () -> { boundary.requireBound();
            return jdbc.update("UPDATE tenant_spike.invoice SET title='denied' WHERE id=?",INVOICE_B); }));
        assertEquals(0, inTenant(A, () -> { boundary.requireBound();
            return jdbc.update("DELETE FROM tenant_spike.invoice WHERE id=?",INVOICE_B); }));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> inTenant(A, () -> {
            boundary.requireBound(); return jdbc.update("UPDATE tenant_spike.invoice SET tenant_id=? WHERE id=?",B,INVOICE_A); }));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> inTenant(B, () -> {
            boundary.requireBound(); return jdbc.update("INSERT INTO tenant_spike.line VALUES (?,?,0)",B,INVOICE_A); }));
        assertEquals(1, inTenant(A, () -> { boundary.requireBound();
            return jdbc.update("INSERT INTO tenant_spike.line VALUES (?,?,0)",A,INVOICE_A); }));
        assertEquals(0L,inTenant(B,()->{boundary.requireBound();return jdbc.queryForObject("SELECT count(*) FROM tenant_spike.line",Long.class);}));
    }

    @Test void requiredAndRequiresNewBindDifferentConnectionsAndRestoreOuterContext() {
        var fresh=new TransactionTemplate(manager); fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        inTenant(A,()->{
            boundary.requireBound(); int outer=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
            int inner=fresh.execute(status->{boundary.requireBound();assertEquals(1L,store.visibleCount());return jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);});
            assertNotEquals(outer,inner); assertTrue(boundary.isBound()); assertEquals(1L,store.visibleCount());
            assertEquals(outer,jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
            code("TENANT_CONTEXT_MISMATCH",()->boundary.select(B));
            assertThrows(IllegalStateException.class,()->fresh.execute(status->{boundary.requireBound();throw new IllegalStateException("fixture failure");}));
            assertEquals(1L,store.visibleCount()); return null;
        });
        assertEquals(1L,inTenant(B,store::visibleCount));
    }

    @Test void caughtInnerRequiredFailureMakesOuterTransactionRollbackOnly() {
        assertThrows(org.springframework.transaction.UnexpectedRollbackException.class,()->inTenant(A,()->{
            try {context.getBean(ManagedUsecases.Inner.class).execute(INVOICE_B);}
            catch(TenantBoundary.Failure expected) {assertEquals("NOT_FOUND",expected.getMessage());}
            return null;
        }));
        assertEquals(1L,inTenant(B,store::visibleCount));
    }

    @Test void innerPermissionIsCheckedOnlyWhenReachedAndBeforeItsBody() {
        assertTrue(AopUtils.isAopProxy(context.getBean(ManagedUsecases.Inner.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(ManagedUsecases.Policy.class)));
        authenticate(false);
        inTenant(A,()->{context.getBean(ManagedUsecases.Outer.class).execute(INVOICE_A,false);return null;});
        assertEquals(List.of("outer"),trace.events);
        long beforeDeniedVersion = inTenant(A, () -> store.byId(INVOICE_A).version);
        trace.events.clear();
        assertThrows(AccessDeniedException.class,()->inTenant(A,()->{context.getBean(ManagedUsecases.Outer.class).execute(INVOICE_A,true);return null;}));
        assertEquals(List.of("outer","policy"),trace.events);
        assertEquals("outer update",inTenant(A,()->store.byId(INVOICE_A).title));
        assertEquals(beforeDeniedVersion, inTenant(A, () -> store.byId(INVOICE_A).version));
        authenticate(true); trace.events.clear();
        inTenant(A,()->{context.getBean(ManagedUsecases.Outer.class).execute(INVOICE_A,true);return null;});
        assertEquals(List.of("outer","policy","body"),trace.events);
        assertEquals("inner update",inTenant(A,()->store.byId(INVOICE_A).title));
    }

    @Test void ownershipAndRollbackPreventRetaggingWhileStaleVersionsStillConflict() {
        var loaded=inTenant(A,()->store.byId(INVOICE_A));
        code("TENANT_OWNERSHIP_MISMATCH",()->inTenant(B,()->{store.save(loaded);return null;}));
        code("TENANT_OWNERSHIP_MISMATCH",()->inTenant(B,()->{store.delete(loaded);return null;}));
        code("TENANT_OWNERSHIP_MISMATCH",()->inTenant(A,()->{store.save(InvoiceStore.Invoice.detached(UUID.randomUUID(),"detached"));return null;}));
        var newlyCreated=InvoiceStore.Invoice.create("rolled back");
        assertThrows(IllegalStateException.class,()->inTenant(A,()->{store.save(newlyCreated);throw new IllegalStateException("rollback");}));
        assertEquals(TenantId.of(A),newlyCreated.ownership.tenant().orElseThrow());
        code("TENANT_OWNERSHIP_MISMATCH",()->inTenant(B,()->{store.save(newlyCreated);return null;}));
        var stale=inTenant(A,()->store.byId(INVOICE_A));
        inTenant(A,()->{var current=store.byId(INVOICE_A);current.title="new version";store.save(current);return null;});
        code("OPTIMISTIC_CONFLICT",()->inTenant(A,()->{store.save(stale);return null;}));
    }

    @Test void sameThreadAndPooledConnectionsDoNotLeakAcrossRepeatedRequestsOrRollback() {
        for(int i=0;i<12;i++) {
            UUID tenant=i%2==0?A:B;
            assertEquals(1L,inTenant(tenant,store::visibleCount));
            assertFalse(boundary.isBound());assertFalse(boundary.isSelected());
        }
        assertThrows(IllegalStateException.class,()->inTenant(A,()->{store.visibleCount();throw new IllegalStateException("rollback");}));
        assertEquals("B title",inTenant(B,()->store.byId(INVOICE_B).title));
    }

    @Test void twoThreadsHaveIndependentSecuritySelectionsAndPhysicalTransactions() throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(A,B).stream().map(tenant->executor.submit(()->{
                authenticate(true);
                try {return inTenant(tenant,()->{boundary.requireBound();try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("barrier");}
                    return jdbc.queryForObject("SELECT current_setting('vernac.tenant_id',true)",String.class);});}
                finally {SecurityContextHolder.clearContext();}
            })).toList();
            assertEquals(A.toString(),tasks.get(0).get(10,TimeUnit.SECONDS));
            assertEquals(B.toString(),tasks.get(1).get(10,TimeUnit.SECONDS));
        }
    }

    @Test void staleSessionTenantAndMidTransactionSettingTamperingAreRejected() throws Exception {
        try(var isolated=fixture.pool(fixture.runtime)) {
            isolated.setMaximumPoolSize(1);
            var sql=new JdbcTemplate(isolated);
            sql.queryForObject("SELECT set_config('vernac.tenant_id',?,false)",String.class,A.toString());
            try {
                code("TENANT_CONTEXT_CONTAMINATED",()->new TenantBoundary(isolated,(a,t)->true));
            } finally {sql.queryForObject("SELECT set_config('vernac.tenant_id','',false)",String.class);}
        }
        code("TENANT_CONTEXT_MISMATCH",()->inTenant(A,()->{
            boundary.requireBound();jdbc.queryForObject("SELECT set_config('vernac.tenant_id',?,true)",String.class,B.toString());
            return store.visibleCount();
        }));
    }
}
