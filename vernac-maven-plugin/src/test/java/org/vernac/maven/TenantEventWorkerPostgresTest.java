// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.*;
import org.vernac.runtime.events.*;
import org.vernac.runtime.jdbc.*;
import org.vernac.runtime.tenancy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real generated modules consumed from a JAR, restricted pooled connections and the production worker. */
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
@Timeout(45)
class TenantEventWorkerPostgresTest extends ModuleMigrationFixture {
    private static final TenantId A=TenantId.of(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    private static final TenantId B=TenantId.of(UUID.fromString("00000000-0000-4000-8000-000000000002"));
    private static final String JOBS="\"vernac\".\"@delivery\"";
    private interface Scenario {void run(CountingSource migrations,RuntimeLogin runtime,URLClassLoader code) throws Exception;}

    private void withRuntime(Scenario scenario) throws Exception {
        tenant=true;durable=true;fixtures();ModuleDeferredEventProbe.calls.set(1);
        try {
            database(migrations->{
                var runtime=provisionRuntime(migrations);var admin=new JdbcTemplate(migrations);
                admin.execute("CREATE SCHEMA vernac");admin.execute("GRANT USAGE ON SCHEMA vernac TO "+runtime.role());
                admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA vernac GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO "+runtime.role());
                try(var code=jar()) {scenario.run(migrations,runtime,code);}
                finally {admin.execute("DROP OWNED BY "+runtime.role());admin.execute("DROP ROLE "+runtime.role());}
            });
        } finally {ModuleDeferredEventProbe.afterWriteObserver=()->{};ModuleDeferredEventProbe.calls.set(0);}
    }
    private URLClassLoader jar() throws Exception {
        Path file=root.resolve("tenant-event-fixture.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(file));var files=Files.walk(resources)) {
            for(var path:files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(resources.relativize(path).toString().replace('\\','/')));
                Files.copy(path,output);output.closeEntry();
            }
        }
        return new URLClassLoader(new java.net.URL[]{file.toUri().toURL()},getClass().getClassLoader());
    }
    private org.springframework.boot.test.context.runner.ApplicationContextRunner pooled(CountingSource migrations,RuntimeLogin runtime,URLClassLoader code) {
        return boot(migrations,runtime,code).withPropertyValues("spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
                "spring.datasource.hikari.maximum-pool-size=3","spring.datasource.hikari.minimum-idle=0");
    }
    private Object purchase(ApplicationContext context,ClassLoader code,TenantId tenant,String text) throws Exception {
        Class<?> name=code.loadClass("db.required.domain.Name");Object usecase=context.getBean("db.application.usecase.Purchase");
        try(var selected=TenantContext.openVerified(tenant)) {
            try{return usecase.getClass().getMethod("execute",name).invoke(usecase,name.getMethod("of",String.class).invoke(null,text));}
            catch(InvocationTargetException failed){if(failed.getCause() instanceof RuntimeException error)throw error;throw failed;}
        }
    }
    private JdbcEventWorker worker(ApplicationContext context) {
        return worker(context,List.copyOf(context.getBeansOfType(EventHandler.class).values()));
    }
    private JdbcEventWorker worker(ApplicationContext context,List<EventHandler> handlers) {
        return new JdbcEventWorker(context.getBean(NamedParameterJdbcTemplate.class),context.getBean(PlatformTransactionManager.class),
                ()->handlers,new EventDeliveryOptions(3,Duration.ofMinutes(1),Duration.ofDays(1),Duration.ofDays(1)));
    }
    private void clean(ApplicationContext context) throws Exception {
        assertTrue(TenantContext.currentTenant().isEmpty());assertTrue(TenantContext.transactionTenant().isEmpty());
        var source=context.getBean(javax.sql.DataSource.class);var connections=new ArrayList<Connection>();
        try {
            for(int i=0;i<3;i++)connections.add(source.getConnection());
            for(var c:connections)try(var sql=c.createStatement();var result=sql.executeQuery("SELECT current_setting('vernac.tenant_id',true)")) {
                assertTrue(result.next());assertTrue(result.getString(1)==null||result.getString(1).isEmpty());
            }
        } finally {for(var c:connections)c.close();}
    }
    private long number(JdbcTemplate jdbc,String query) {return jdbc.queryForObject(query,Long.class);}
    private void skipAudit(JdbcTemplate admin) {admin.update("UPDATE "+JOBS+" SET status='SUCCEEDED' WHERE \"listenerId\"='db.required.AuditStarted'");}

    @Test void tenantModuleEventsSurviveRestartAndRetryWithRequiredWritesAndCleanPools() throws Exception {
        withRuntime((migrations,runtime,code)->{
            var admin=new JdbcTemplate(migrations);ModuleDeferredEventProbe.calls.set(0);
            pooled(migrations,runtime,code).run(context->{
                purchase(context,code,A,"A");purchase(context,code,B,"B");
                long roots=number(admin,"SELECT count(*) FROM \"db.required\".\"Root\"");
                // A failing outer usecase rolls back module aggregate, payload and recipient writes together.
                Class<?> name=code.loadClass("db.required.domain.Name");Object rejecting=context.getBean("db.application.usecase.RejectPurchase");
                try(var selected=TenantContext.openVerified(A)) {
                    assertThrows(InvocationTargetException.class,()->rejecting.getClass().getMethod("execute",name).invoke(rejecting,name.getMethod("of",String.class).invoke(null,"rollback")));
                }
                assertEquals(roots,number(admin,"SELECT count(*) FROM \"db.required\".\"Root\""));
                assertEquals(2,number(admin,"SELECT count(*) FROM \"db.required\".\"@event:Started\""));
                assertEquals(4,number(admin,"SELECT count(*) FROM "+JOBS));
                var worker=worker(context);while(worker.runOnce()){}
                assertEquals(1,number(admin,"SELECT count(*) FROM "+JOBS+" WHERE status='PENDING'"));
                assertEquals(DomainValidationException.class.getName(),admin.queryForObject("SELECT \"lastError\" FROM "+JOBS+" WHERE status='PENDING'",String.class));clean(context);
            });
            pooled(migrations,runtime,code).run(context->{
                admin.update("UPDATE "+JOBS+" SET \"nextAttemptAt\"=clock_timestamp() WHERE status='PENDING'");
                var observed=new ArrayList<TenantId>();ModuleDeferredEventProbe.afterWriteObserver=()->observed.add(TenantContext.requireTenant());
                var worker=worker(context);while(worker.runOnce()){}
                assertEquals(1,observed.size());assertTrue(Set.of(A,B).contains(observed.getFirst()));
                assertEquals(4,number(admin,"SELECT count(*) FROM "+JOBS+" WHERE status='SUCCEEDED'"));
                assertEquals(1,number(admin,"SELECT count(*) FROM "+JOBS+" WHERE attempts=2"));
                for(var t:List.of(A,B))assertEquals(1,admin.queryForObject("SELECT count(*) FROM \"db.dependent\".\"Receipt\" WHERE \"name.text\"='delivered' AND \"@tenantId\"=?",Long.class,t.value()));
                clean(context);
            });
        });
    }

    @Test void parallelWorkersUseDifferentTenantConnectionsAndPreserveOrigin() throws Exception {
        withRuntime((migrations,runtime,code)->pooled(migrations,runtime,code).run(context->{
            var admin=new JdbcTemplate(migrations);purchase(context,code,A,"A");purchase(context,code,B,"B");skipAudit(admin);
            var barrier=new CyclicBarrier(2);var connections=new ConcurrentHashMap<TenantId,Integer>();
            ModuleDeferredEventProbe.afterWriteObserver=()->{
                connections.put(TenantContext.requireTenant(),context.getBean(JdbcTemplate.class).queryForObject("SELECT pg_backend_pid()",Integer.class));
                try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception failed){throw new IllegalStateException("worker rendezvous failed",failed);}
            };
            var worker=worker(context);
            try(var executor=Executors.newFixedThreadPool(2)) {
                var one=executor.submit(worker::runOnce);var two=executor.submit(worker::runOnce);
                assertTrue(one.get(10,TimeUnit.SECONDS));assertTrue(two.get(10,TimeUnit.SECONDS));
            }
            assertEquals(Set.of(A,B),connections.keySet());assertEquals(2,new HashSet<>(connections.values()).size());
            assertEquals(2,number(admin,"SELECT count(*) FROM \"db.dependent\".\"Receipt\" WHERE \"name.text\"='delivered'"));clean(context);
        }));
    }

    @Test void expiredTenantLeaseRollsBackOldEffectsAndFinalAttemptCrashesTerminate() throws Exception {
        withRuntime((migrations,runtime,code)->pooled(migrations,runtime,code).run(context->{
            var admin=new JdbcTemplate(migrations);purchase(context,code,A,"A");skipAudit(admin);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            ModuleDeferredEventProbe.afterWriteObserver=()->{
                assertEquals(A,TenantContext.requireTenant());
                if(calls.getAndIncrement()==0){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException failed){throw new IllegalStateException(failed);}}
            };
            var worker=worker(context);
            try(var executor=Executors.newFixedThreadPool(2)) {
                var old=executor.submit(worker::runOnce);
                try {
                    assertTrue(entered.await(10,TimeUnit.SECONDS));
                    admin.update("UPDATE "+JOBS+" SET \"leaseUntil\"=clock_timestamp()-interval '1 second' WHERE status='PROCESSING'");
                    assertTrue(executor.submit(worker::runOnce).get(10,TimeUnit.SECONDS));
                } finally {release.countDown();}
                assertTrue(old.get(10,TimeUnit.SECONDS));
            }
            assertEquals(1,number(admin,"SELECT count(*) FROM \"db.dependent\".\"Receipt\" WHERE \"name.text\"='delivered'"));
            purchase(context,code,B,"B");skipAudit(admin);
            admin.update("UPDATE "+JOBS+" SET status='PROCESSING',attempts=3,\"claimToken\"=?,\"leaseUntil\"=clock_timestamp()-interval '1 second' WHERE status='PENDING'",UUID.randomUUID());
            assertFalse(worker.runOnce());assertEquals(1,number(admin,"SELECT count(*) FROM "+JOBS+" WHERE status='FAILED'"));clean(context);
        }));
    }

    @Test void forgedTenantOrGlobalRoutingNeverInvokesTheTenantPayloadReceiver() throws Exception {
        withRuntime((migrations,runtime,code)->pooled(migrations,runtime,code).run(context->{
            var admin=new JdbcTemplate(migrations);purchase(context,code,A,"A");skipAudit(admin);
            var invoked=new AtomicInteger();ModuleDeferredEventProbe.afterWriteObserver=invoked::incrementAndGet;
            admin.update("UPDATE "+JOBS+" SET \"tenantId\"=? WHERE status='PENDING'",B.value());
            var worker=worker(context);assertTrue(worker.runOnce());assertEquals(0,invoked.get());
            admin.update("UPDATE "+JOBS+" SET \"tenantId\"=NULL,\"nextAttemptAt\"=clock_timestamp() WHERE status='PENDING'");
            assertTrue(worker.runOnce());assertEquals(0,invoked.get());clean(context);
            admin.update("UPDATE "+JOBS+" SET \"tenantId\"=?,\"nextAttemptAt\"=clock_timestamp() WHERE status='PENDING'",A.value());
            assertTrue(worker.runOnce());assertEquals(1,invoked.get());clean(context);
        }));
    }

    @Test void legacyStorageCapabilityIsRejectedBeforePublicationOrTenantPayloadLoading() throws Exception {
        withRuntime((migrations,runtime,code)->pooled(migrations,runtime,code).run(context->{
            var actual=context.getBeansOfType(EventStorage.class).values().iterator().next();var invoked=new AtomicInteger();
            EventStorage legacy=new EventStorage(){
                public String eventType(){return actual.eventType();}public String listenerId(){return actual.listenerId();}
                public Class<? extends DomainEvent> eventClass(){return actual.eventClass();}
                public void persist(DomainEvent event,List<String> recipients){invoked.incrementAndGet();}
                public DomainEvent load(EventId id){invoked.incrementAndGet();throw new AssertionError("Legacy tenant payload load");}
            };
            purchase(context,code,A,"A");
            DomainEvent event;try(var selected=TenantContext.openVerified(A)) {
                event=new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(s->actual.load(EventId.of(
                        new JdbcTemplate(migrations).queryForObject("SELECT \"@eventId\" FROM \"db.required\".\"@event:Started\"",UUID.class))));
                assertEquals(TenancyException.Code.TENANT_EVENT_STORAGE_REBUILD_REQUIRED,assertThrows(TenancyException.class,()->
                        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(s->{
                            try(var events=EventContext.open(()->List.of(legacy))){EventContext.emit(event);}
                        })).code());
            }
            var handlers=new ArrayList<EventHandler>(context.getBeansOfType(EventHandler.class).values());handlers.removeIf(EventStorage.class::isInstance);handlers.add(legacy);
            var worker=worker(context,handlers);while(worker.runOnce()){}
            assertEquals(0,invoked.get());assertEquals(2,number(new JdbcTemplate(migrations),"SELECT count(*) FROM "+JOBS+" WHERE status='BLOCKED' AND attempts=0"));clean(context);
        }));
    }

    @Test void weakenedEventPolicyAbortsThePublishingUsecaseWithoutLeakingRows() throws Exception {
        withRuntime((migrations,runtime,code)->pooled(migrations,runtime,code).run(context->{
            var admin=new JdbcTemplate(migrations);
            admin.execute("DROP POLICY \"@event-origin\" ON \"db.required\".\"@event:Started\"");
            admin.execute("CREATE POLICY \"@event-origin\" ON \"db.required\".\"@event:Started\" USING (true) WITH CHECK (true)");
            var error=assertThrows(TenancyException.class,()->purchase(context,code,A,"must roll back"));
            assertEquals(TenancyException.Code.TENANT_RUNTIME_ROLE_INVALID,error.code());
            assertEquals(0,number(admin,"SELECT count(*) FROM \"db.required\".\"Root\""));
            assertEquals(0,number(admin,"SELECT count(*) FROM \"db.required\".\"@event:Started\""));assertEquals(0,number(admin,"SELECT count(*) FROM "+JOBS));clean(context);
        }));
    }
    @Test void deliveryUpgradeRetainsPendingGlobalJobsAndRejectsAnUnmigratedQueue() throws Exception {
        withRuntime((migrations,runtime,code)->pooled(migrations,runtime,code).run(context->{
            var admin=new JdbcTemplate(migrations);var storage=context.getBeansOfType(EventStorage.class).values().iterator().next();
            var idType=code.loadClass("db.required.domain.RootId");
            DomainEvent event=(DomainEvent)storage.eventClass().getMethod("create",idType).invoke(null,idType.getMethod("create").invoke(null));
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status->storage.persist(event,List.of("migration.probe")));
            admin.execute("ALTER TABLE "+JOBS+" DROP COLUMN \"tenantId\"");
            assertThrows(VernacTechnicalException.class,worker(context)::runOnce);
            assertThrows(VernacTechnicalException.class,()->tx.executeWithoutResult(status->storage.persist(event,List.of("other"))));
            assertEquals(1,number(admin,"SELECT count(*) FROM "+JOBS+" WHERE attempts=0"));
            var json=org.vernac.compiler.persistence.SchemaModel.JSON;
            var current=json.treeToValue(json.readTree(Files.readString(resources.resolve("application-schema.json"))).get("schema"),org.vernac.compiler.persistence.SchemaModel.class);
            var previous=new org.vernac.compiler.persistence.SchemaModel(current.formatVersion(),current.mappingVersion(),current.tables().stream().map(t->
                    t.name().equals("@delivery")?new org.vernac.compiler.persistence.SchemaModel.Table(t.namespace(),t.name(),t.origin(),
                        t.columns().stream().filter(c->!c.name().equals("tenantId")).toList(),t.primaryKey(),t.constraints()):t).toList(),current.enumCodes());
            var migration=new org.vernac.compiler.persistence.MigrationPlanner().plan(previous,current,org.vernac.compiler.persistence.MigrationPlanner.Options.safe());
            assertTrue(migration.sql().contains("DELIVERY ORIGIN UPGRADE"));admin.execute(migration.sql());
            assertEquals(1,number(admin,"SELECT count(*) FROM "+JOBS+" WHERE \"tenantId\" IS NULL AND attempts=0"));
            var calls=new AtomicInteger();
            EventHandler receiver=new EventHandler(){
                public String listenerId(){return "migration.probe";}public Class<? extends DomainEvent> eventClass(){return storage.eventClass();}
                public boolean deferred(){return true;}public void handle(DomainEvent value){assertTrue(TenantContext.currentTenant().isEmpty());assertEquals(event.eventId(),value.eventId());calls.incrementAndGet();}
            };
            assertTrue(worker(context,List.of(storage,receiver)).runOnce());assertEquals(1,calls.get());clean(context);
        }));
    }

}
