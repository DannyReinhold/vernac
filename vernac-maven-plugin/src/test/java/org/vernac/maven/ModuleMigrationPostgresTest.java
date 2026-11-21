// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.boot.flyway.autoconfigure.*;
import org.flywaydb.core.Flyway;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.application.ApplicationBindings;
import org.vernac.runtime.application.*;
import org.vernac.runtime.jdbc.modules.ModuleMigrationStrategy;
import org.vernac.runtime.persistence.SchemaDescription;
import static org.junit.jupiter.api.Assertions.*;

class ModuleMigrationPostgresTest extends ModuleMigrationFixture {
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
    void bootUsesSeparateMigrationCredentialsAndRestrictedRuntimeRepositories() throws Exception {
        fixtures();
        database(migrations -> {
            RuntimeLogin runtime = provisionRuntime(migrations);
            try (var code = new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, getClass().getClassLoader())) {
                for (int restart = 0; restart < 2; restart++) boot(migrations, runtime, code).run(context -> {
                    assertNull(context.getStartupFailure(), () -> String.valueOf(context.getStartupFailure()));
                    var runtimeSource = context.getBean(javax.sql.DataSource.class);
                    try (var connection = runtimeSource.getConnection(); var sql = connection.createStatement()) {
                        try (var identity = sql.executeQuery("SELECT current_user")) {
                            assertTrue(identity.next()); assertEquals(runtime.role(), identity.getString(1));
                        }
                        assertThrows(SQLException.class, () -> sql.execute("CREATE TABLE public.runtime_must_not_create(id int)"));
                        assertThrows(SQLException.class, () -> sql.executeQuery("SELECT * FROM public.flyway_schema_history"));
                    }
                    var name = code.loadClass("db.required.domain.Name");
                    var submit = context.getBean("db.dependent.Submit");
                    submit.getClass().getMethod("execute", name).invoke(submit,
                            name.getMethod("of", String.class).invoke(null, "separate credentials"));
                    assertSame(runtimeSource, context.getBean(NamedParameterJdbcTemplate.class).getJdbcTemplate().getDataSource());
                    assertNotSame(runtimeSource, context.getBean(Flyway.class).getConfiguration().getDataSource());
                });
                assertEquals(2, count(migrations, "SELECT count(*) FROM \"db.required\".\"Root\""));
                assertEquals(2, count(migrations, "SELECT count(*) FROM \"db.dependent\".\"Receipt\""));
                assertEquals(3, count(migrations, "SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history'"));
                assertEquals(0, count(migrations, "SELECT count(*) FROM pg_tables WHERE tableowner='" + runtime.role() + "'"));
            } finally {
                try (var connection = migrations.getConnection(); var sql = connection.createStatement()) {
                    sql.execute("DROP OWNED BY " + runtime.role()); sql.execute("DROP ROLE " + runtime.role());
                }
            }
        });
    }

    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
    void differentMigrationDatabaseFailsBeforeAnyHistoryOrSchemaIsWritten() throws Exception {
        fixtures();
        database(runtime -> database(migrations -> {
            try (var code = new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, getClass().getClassLoader());
                 var context = context(runtime, migrations, code)) {
                var error = assertThrows(RuntimeException.class, context::refresh);
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                assertTrue(cause.getMessage().contains("same PostgreSQL database instance"));
            }
            for (var source : List.of(runtime, migrations)) {
                assertEquals(0, count(source, "SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history' OR table_schema IN ('db.required','db.dependent','db.application')"));
                assertEquals(0, source.transactions.get());
            }
        }));
    }

    @Test void fixtureModulesCompileAndShareTheRuntimeFingerprintContract() throws Exception {
        var models=fixtures();assertEquals(2,models.size());
        assertTrue(Files.isRegularFile(resources.resolve("db/dependent/usecase/Submit.class")));
        assertTrue(Files.isRegularFile(resources.resolve("application-schema.json")));
        upgraded=true;descriptors.clear();javaSources.clear();fixtures();
        assertTrue(Files.readString(resources.resolve("db/migration/V20261013220100000__add_origin.sql")).contains("LEGACY"));
    }
    @Test void durableFixturesKeepPayloadAndSharedDeliveryOwnershipSeparate() throws Exception {
        durable=true;var modules=fixtures();
        assertTrue(modules.getFirst().schema().tables().stream().anyMatch(t->t.name().equals("@event:Started")));
        for(var module:modules)assertTrue(module.schema().tables().stream().noneMatch(t->t.identity().equals("vernac/@delivery")));
        assertTrue(Files.readString(resources.resolve("application-schema.json")).contains("@delivery"));
        assertTrue(modules.getFirst().descriptor().exports().stream().filter(d->d.kind().equals("EVENT")).allMatch(d->"true".equals(d.javaMembers().get("@recorded"))));
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void deferredModuleReceiversResumeAfterRestartAndRetryWithoutDuplicatingCommittedWrites() throws Exception {
        durable=true;ModuleDeferredEventProbe.calls.set(0);fixtures();
        database(source->{
            // Reproduce a CI login named vernac even when the test server uses another login.
            // Before schema creation public is current; afterwards vernac would become current.
            source.searchPath="vernac, public";
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();
                assertEquals(1,count(source,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND type='SQL'"));
                assertEquals(0,count(source,"SELECT count(*) FROM information_schema.tables WHERE table_schema='vernac' AND table_name='flyway_schema_history'"));
                var name=code.loadClass("db.required.domain.Name");
                Object submit=context.getBean("db.dependent.Submit");
                submit.getClass().getMethod("execute",name).invoke(submit,name.getMethod("of",String.class).invoke(null,"durable"));
                assertEquals(2,count(source,"SELECT count(*) FROM \"vernac\".\"@delivery\" WHERE status='PENDING'"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"@event:Started\""));
                assertEquals(0,ModuleDeferredEventProbe.calls.get());
            }
            // No background worker was started by this plain Spring fixture. Pending jobs survive restart.
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();
                var worker=new org.vernac.runtime.jdbc.JdbcEventWorker(context.getBean(NamedParameterJdbcTemplate.class),context.getBean(DataSourceTransactionManager.class),
                    ()->List.copyOf(context.getBeansOfType(org.vernac.runtime.events.EventHandler.class).values()),org.vernac.runtime.jdbc.EventDeliveryOptions.defaults());
                for(int i=0;i<6 && worker.runOnce();i++) {
                    // Expedite the deliberate retry, rather than sleeping through production backoff.
                    context.getBean(NamedParameterJdbcTemplate.class).update("UPDATE \"vernac\".\"@delivery\" SET \"nextAttemptAt\"=clock_timestamp() WHERE status='PENDING'",Map.of());
                }
                assertEquals(2,ModuleDeferredEventProbe.calls.get());
                assertEquals(2,count(source,"SELECT count(*) FROM \"vernac\".\"@delivery\" WHERE status='SUCCEEDED'"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"vernac\".\"@delivery\" WHERE attempts=2"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"db.dependent\".\"Receipt\" WHERE \"name.text\"='delivered'"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"@event:Started\""),"Recorded event remains as a protocol row");
                assertFalse(worker.runOnce());
            }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void freshStartupRestartSeparateHistoriesAndRequiredTransactions() throws Exception {
        var modules=fixtures();database(source->{
            for(int restart=0;restart<2;restart++)try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();
                // Flyway also records schema creation (type SCHEMA); count the actual SQL migration.
                for(var module:modules)assertEquals(1,count(source,"SELECT count(*) FROM "+SqlNames.quote(ModulePersistence.historySchema(module.descriptor().module()))+".flyway_schema_history WHERE success AND type = 'SQL' AND version = '20261013210000000'"));
                assertEquals(1,count(source,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND type = 'SQL' AND version = '20261013210000000'"));
                if(restart==0){source.transactions.set(0);var name=code.loadClass("db.required.domain.Name");Object value=name.getMethod("of",String.class).invoke(null,"module fixture");
                    Object submit=context.getBean("db.dependent.Submit");submit.getClass().getMethod("execute",name).invoke(submit,value);assertEquals(1,source.transactions.get());
                    assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"Root\""));assertEquals(1,count(source,"SELECT count(*) FROM \"db.dependent\".\"Receipt\""));
                    Object reject=context.getBean("db.dependent.Reject");assertThrows(java.lang.reflect.InvocationTargetException.class,()->reject.getClass().getMethod("execute",name).invoke(reject,value));
                    assertEquals(2,source.transactions.get());assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"Root\""));assertEquals(1,count(source,"SELECT count(*) FROM \"db.dependent\".\"Receipt\""));}
            }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void failedLaterHistoryStopsApplicationAndRetryRetainsEarlierSuccess() throws Exception {
        var modules=fixtures();var second=modules.get(1);Path file=second.sql().resolve("V20261013210000000__initial.sql");String reviewed=Files.readString(file);Files.writeString(file,reviewed+"\nSELECT 1/0;\n");bundle("dependent",second.schema(),root.resolve("dependent/history"),second.sql());
        database(source->{
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)){assertThrows(RuntimeException.class,context::refresh);}
            assertEquals(1,count(source,"SELECT count(*) FROM "+SqlNames.quote(ModulePersistence.historySchema("example.required"))+".flyway_schema_history WHERE success AND type = 'SQL' AND version = '20261013210000000'"));
            assertEquals(0,count(source,"SELECT count(*) FROM information_schema.tables WHERE table_schema='db.application' AND table_name='Application'"));
            Files.writeString(file,reviewed);bundle("dependent",second.schema(),root.resolve("dependent/history"),second.sql());
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)){context.refresh();assertEquals(1,count(source,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND type = 'SQL' AND version = '20261013210000000'"));}
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void upgradesModuleAndApplicationCopiesOfAnExternalValueWithExistingData() throws Exception {
        fixtures();var oldDescriptors=List.copyOf(descriptors);Path oldResources=root.resolve("version-one");
        try(var files=Files.walk(resources)) {for(Path file:files.filter(Files::isRegularFile).toList()) {
            Path copy=oldResources.resolve(resources.relativize(file));Files.createDirectories(copy.getParent());Files.copy(file,copy);
        }}
        database(source->{
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();var name=code.loadClass("db.required.domain.Name");
                context.getBean("db.dependent.Submit").getClass().getMethod("execute",name)
                    .invoke(context.getBean("db.dependent.Submit"),name.getMethod("of",String.class).invoke(null,"before upgrade"));
            }
            // Both roots have the same persisted columns. Seed a local application copy.
            try(var c=source.getConnection();var statement=c.createStatement()) {
                String columns=List.of("@createdAt","@nanoRemainder:@createdAt","@nanoRemainder:@updatedAt",
                    "@updatedAt","@version","id","name.alias","name.text").stream().map(SqlNames::quote).collect(java.util.stream.Collectors.joining(","));
                statement.execute("INSERT INTO \"db.application\".\"Application\" ("+columns+") SELECT "+columns+" FROM \"db.required\".\"Root\"");
            }
            upgraded=true;descriptors.clear();javaSources.clear();var modules=fixtures();
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();
                for(String table:List.of("\"db.required\".\"Root\"","\"db.dependent\".\"Receipt\"","\"db.application\".\"Application\""))
                    assertEquals(1,count(source,"SELECT count(*) FROM "+table+" WHERE \"name.origin\"='LEGACY' AND \"name.text\"='before upgrade'"));
                for(var module:modules)assertEquals(2,count(source,"SELECT count(*) FROM "+SqlNames.quote(ModulePersistence.historySchema(module.descriptor().module()))+".flyway_schema_history WHERE success AND type='SQL'"));
                assertEquals(2,count(source,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND type='SQL'"));
                var name=code.loadClass("db.required.domain.Name");
                context.getBean("db.dependent.Submit").getClass().getMethod("execute",name)
                    .invoke(context.getBean("db.dependent.Submit"),name.getMethod("of",String.class,String.class).invoke(null,"after upgrade","CURRENT"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"Root\" WHERE \"name.origin\"='CURRENT'"));
            }
            resources=oldResources;descriptors.clear();descriptors.addAll(oldDescriptors);
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                assertThrows(RuntimeException.class,context::refresh,"An older artifact must not start against upgraded histories");
            }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void synchronousModuleListenerFailureRollsBackTheCallingTransaction() throws Exception {
        fixtures();database(source->{
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();var name=code.loadClass("db.required.domain.Name");
                var submit=context.getBean("db.dependent.Submit");
                var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->submit.getClass().getMethod("execute",name)
                    .invoke(submit,name.getMethod("of",String.class).invoke(null,"reject")));
                assertInstanceOf(org.vernac.runtime.DomainValidationException.class,error.getCause());
                assertEquals(0,count(source,"SELECT count(*) FROM \"db.required\".\"Root\""));
                assertEquals(0,count(source,"SELECT count(*) FROM \"db.dependent\".\"Receipt\""));
                assertEquals(0,count(source,"SELECT count(*) FROM information_schema.tables WHERE table_schema='vernac' AND table_name='@delivery'"));
            }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void manualOwnedSchemaDriftBlocksRestart() throws Exception {
        fixtures();database(source->{
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)){context.refresh();}
            try(var c=source.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE \"db.required\".\"Root\" ADD COLUMN unexpected TEXT");}
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)){assertThrows(RuntimeException.class,context::refresh);}
        });
    }
}
