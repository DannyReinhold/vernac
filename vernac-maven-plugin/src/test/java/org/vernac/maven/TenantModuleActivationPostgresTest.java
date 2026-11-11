// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.compiler.modules.ModulePersistence;
import org.vernac.compiler.persistence.SchemaHistory;
import org.vernac.runtime.AggregateNotFoundException;
import org.vernac.runtime.tenancy.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
class TenantModuleActivationPostgresTest extends ModuleMigrationFixture {
    private static final TenantId A = TenantId.of(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    private static final TenantId B = TenantId.of(UUID.fromString("00000000-0000-4000-8000-000000000002"));
    private static final List<String> TABLES = List.of("\"db.required\".\"Root\"", "\"db.dependent\".\"Receipt\"", "\"db.application\".\"Application\"");
    private interface Scenario {void run(CountingSource migrations, RuntimeLogin runtime, URLClassLoader code) throws Exception;}

    private void withRuntime(Scenario scenario) throws Exception {
        tenant = true; fixtures();
        database(migrations -> {
            RuntimeLogin runtime = provisionRuntime(migrations);
            try (var code = code()) { scenario.run(migrations, runtime, code); }
            finally {
                try (var connection = migrations.getConnection(); var sql = connection.createStatement()) {
                    sql.execute("DROP OWNED BY " + runtime.role()); sql.execute("DROP ROLE " + runtime.role());
                }
            }
        });
    }

    private URLClassLoader code() throws Exception {
        return new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object invoke(ApplicationContext context, ClassLoader code, TenantId tenant, String operation, String text) throws Exception {
        var name = code.loadClass("db.required.domain.Name");
        Object value = upgraded ? name.getMethod("of", String.class, String.class).invoke(null, text, "CURRENT")
                : name.getMethod("of", String.class).invoke(null, text);
        Object usecase = context.getBean("db.application.usecase." + operation);
        try (var selected = TenantContext.openVerified(tenant)) {
            try { return usecase.getClass().getMethod("execute", name).invoke(usecase, value); }
            catch (InvocationTargetException failed) {
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                throw failed;
            }
        }
    }

    private Object load(ApplicationContext context, ClassLoader code, TenantId tenant, Object id) throws Exception {
        var repository = context.getBean("db.required.RootRepository");
        var method = repository.getClass().getMethod("byId", code.loadClass("db.required.domain.RootId"));
        try (var selected = TenantContext.openVerified(tenant)) {
            return new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
                try { return method.invoke(repository, id); }
                catch (InvocationTargetException failed) {throw (RuntimeException) failed.getCause();}
                catch (ReflectiveOperationException failed) {throw new IllegalStateException(failed);}
            });
        }
    }

    private void assertCounts(CountingSource source, int expected) throws Exception {
        for (String table : TABLES) assertEquals(expected, count(source, "SELECT count(*) FROM " + table));
    }

    private void assertHistories(CountingSource source, int expected) throws Exception {
        for (String schema : List.of(ModulePersistence.historySchema("example.required"), ModulePersistence.historySchema("example.dependent"), "public"))
            assertEquals(expected, count(source, "SELECT count(*) FROM \"" + schema + "\".flyway_schema_history WHERE success AND type='SQL'"));
    }

    @Test void tenantModulesStartRestartAndShareRequiredTransactionsWithTheApplication() throws Exception {
        withRuntime((migrations, runtime, code) -> {
            List<Object> ids = new ArrayList<>();
            boot(migrations, runtime, code).run(context -> {
                ids.add(invoke(context, code, A, "Purchase", "A"));
                ids.add(invoke(context, code, B, "Purchase", "B"));
                assertNotNull(load(context, code, A, ids.getFirst()));
                assertThrows(AggregateNotFoundException.class, () -> load(context, code, A, ids.getLast()));
                assertThrows(IllegalStateException.class, () -> invoke(context, code, A, "RejectPurchase", "rolled back"));
                var jdbc = context.getBean(JdbcTemplate.class);
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"db.required\".\"Root\"", Integer.class));
                assertTrue(TenantContext.currentTenant().isEmpty());
            });
            assertCounts(migrations, 2); assertHistories(migrations, 1);
            boot(migrations, runtime, code).run(context -> {
                assertNotNull(load(context, code, A, ids.getFirst()));
                assertNotNull(load(context, code, B, ids.getLast()));
            });
            assertCounts(migrations, 2); assertHistories(migrations, 1);
        });
    }

    private void sql(CountingSource source, String statement) throws Exception {
        try (var connection = source.getConnection(); var sql = connection.createStatement()) { sql.execute(statement); }
    }

    private void assertTenantStartupFailure(CountingSource source, RuntimeLogin runtime, URLClassLoader code) {
        var initialized = new java.util.concurrent.atomic.AtomicInteger();
        boot(source, runtime, code).withInitializer(context -> context.getBeanFactory().addBeanPostProcessor(
                new org.springframework.beans.factory.config.BeanPostProcessor() {
                    public Object postProcessBeforeInitialization(Object bean, String name) {
                        if (name.startsWith("db.required.") || name.startsWith("db.dependent.")) initialized.incrementAndGet();
                        return bean;
                    }
                })).run(context -> {
            Throwable error = context.getStartupFailure(); assertNotNull(error);
            while (error != null && !(error instanceof TenancyException)) error = error.getCause();
            assertInstanceOf(TenancyException.class, error);
            assertEquals(0, initialized.get(), "Module components must wait for the verified migration gate");
        });
    }

    @Test void tenantPolicyAndRuntimeRoleDriftPreventCallableModuleStartup() throws Exception {
        withRuntime((migrations, runtime, code) -> {
            boot(migrations, runtime, code).run(context -> assertNull(context.getStartupFailure()));
            String table = TABLES.getFirst();
            sql(migrations, "ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
            assertTenantStartupFailure(migrations, runtime, code);
            sql(migrations, "ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            sql(migrations, "ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
            assertTenantStartupFailure(migrations, runtime, code);
            sql(migrations, "ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            sql(migrations, "ALTER POLICY \"@tenant\" ON " + table + " USING (true) WITH CHECK (true)");
            assertTenantStartupFailure(migrations, runtime, code);
            String predicate = "\"@tenantId\" = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid";
            sql(migrations, "ALTER POLICY \"@tenant\" ON " + table + " USING (" + predicate + ") WITH CHECK (" + predicate + ")");
            sql(migrations, "REVOKE UPDATE ON " + table + " FROM " + runtime.role());
            assertTenantStartupFailure(migrations, runtime, code);
            sql(migrations, "GRANT UPDATE ON " + table + " TO " + runtime.role());
            assertTenantStartupFailure(migrations, new RuntimeLogin(migrations.credentials.getProperty("user"), migrations.credentials.getProperty("password", "")), code);
            boot(migrations, runtime, code).run(context -> assertNull(context.getStartupFailure()));
            assertCounts(migrations, 0);
        });
    }

    @Test void tenantModuleAndApplicationValueUpgradesBackfillBothTenants() throws Exception {
        withRuntime((migrations, runtime, code) -> {
            boot(migrations, runtime, code).run(context -> {
                invoke(context, code, A, "Purchase", "A"); invoke(context, code, B, "Purchase", "B");
            });
            upgraded = true; descriptors.clear(); javaSources.clear(); fixtures();
            try (var upgradedCode = code()) {
                boot(migrations, runtime, upgradedCode).run(context -> assertNull(context.getStartupFailure()));
                for (String table : TABLES)
                    assertEquals(2, count(migrations, "SELECT count(*) FROM " + table + " WHERE \"name.origin\"='LEGACY'"));
                assertHistories(migrations, 2);
                boot(migrations, runtime, upgradedCode).run(context -> invoke(context, upgradedCode, A, "Purchase", "new value"));
                for (String table : TABLES)
                    assertEquals(1, count(migrations, "SELECT count(*) FROM " + table + " WHERE \"name.origin\"='CURRENT'"));
            }
        });
    }

    @Test void failedTenantModuleMigrationStopsStartupAndRetryKeepsEarlierHistory() throws Exception {
        withRuntime((migrations, runtime, code) -> {
            Path sql = root.resolve("dependent/migration/V20261013210000000__initial.sql");
            String reviewed = Files.readString(sql);
            var model = new SchemaHistory(root.resolve("dependent/history"), sql.getParent()).read();
            Files.writeString(sql, reviewed + "\nSELECT missing_tenant_module_fixture();\n");
            bundle("dependent", model, root.resolve("dependent/history"), sql.getParent());
            boot(migrations, runtime, code).run(context -> assertNotNull(context.getStartupFailure()));
            assertEquals(1, count(migrations, "SELECT count(*) FROM \"" + ModulePersistence.historySchema("example.required") + "\".flyway_schema_history WHERE success AND type='SQL'"));
            assertEquals(0, count(migrations, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='flyway_schema_history'"));
            // Repair only this disposable, deliberately unpublished candidate.
            Files.writeString(sql, reviewed); bundle("dependent", model, root.resolve("dependent/history"), sql.getParent());
            boot(migrations, runtime, code).run(context -> invoke(context, code, A, "Purchase", "after retry"));
            assertHistories(migrations, 1); assertCounts(migrations, 1);
        });
    }

    @Test void packagedTenantModulesVerifyBundlesAndStartThroughGeneratedActivation() throws Exception {
        withRuntime((migrations, runtime, ignored) -> {
            try (var jars = packagedCode()) {
                boot(migrations, runtime, jars).run(context -> {
                    assertNull(context.getStartupFailure());
                    invoke(context, jars, A, "Purchase", "packaged A");
                    invoke(context, jars, B, "Purchase", "packaged B");
                });
                assertCounts(migrations, 2); assertHistories(migrations, 1);
            }
        });
    }

    @Test void parallelTenantModuleRequestsUseIndependentPhysicalTransactions() throws Exception {
        parallel = true; TenantModuleTransactionProbe.barrier = new CyclicBarrier(2);
        try {
            withRuntime((migrations, runtime, code) -> boot(migrations, runtime, code).run(context -> {
                var pool = Executors.newFixedThreadPool(2);
                try {
                    var a = pool.submit(() -> invoke(context, code, A, "Purchase", "A"));
                    var b = pool.submit(() -> invoke(context, code, B, "Purchase", "B"));
                    Object first = a.get(30, TimeUnit.SECONDS), second = b.get(30, TimeUnit.SECONDS);
                    assertNotNull(load(context, code, A, first));
                    assertNotNull(load(context, code, B, second));
                    assertThrows(AggregateNotFoundException.class, () -> load(context, code, A, second));
                    assertCounts(migrations, 2);
                } finally { pool.shutdownNow(); }
            }));
        } finally { TenantModuleTransactionProbe.barrier = null; }
    }
}
