// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.modules;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.flywaydb.core.Flyway;
import org.vernac.runtime.application.*;
import org.vernac.runtime.persistence.SchemaDescription;
import static org.junit.jupiter.api.Assertions.*;
class ModuleCoordinatorTest {
    private static final SchemaDescription EMPTY=new SchemaDescription(1,1,List.of(),Map.of());
    private static ModuleMigrationStrategy.Prepared module(String name,String table){return new ModuleMigrationStrategy.Prepared(name,"classpath:"+name,name,EMPTY,Set.of(table));}
    @Test void implicitApplicationHistoryIsPublicWhileExplicitLocationsStayUnchanged() {
        var original=Flyway.configure().locations("classpath:custom/migrations").table("app_history").load();
        var stable=ModuleMigrationStrategy.stableApplicationHistory(original);
        assertEquals("public",stable.getConfiguration().getDefaultSchema());
        assertEquals("app_history",stable.getConfiguration().getTable());
        assertEquals(original.getConfiguration().getLocations()[0],stable.getConfiguration().getLocations()[0]);
        assertNull(original.getConfiguration().getDefaultSchema(),"Do not mutate the caller's Flyway configuration");
        var explicit=Flyway.configure().defaultSchema("custom_history").load();
        assertSame(explicit,ModuleMigrationStrategy.stableApplicationHistory(explicit));
        var schemas=Flyway.configure().schemas("configured_history","other_schema").load();
        assertSame(schemas,ModuleMigrationStrategy.stableApplicationHistory(schemas));
    }
    @Test void migratesAndValidatesDependenciesBeforeApplicationAndStopsOnFailure() {
        List<String> log=new ArrayList<>();var modules=List.of(module("required","one/table"),module("dependent","two/table"));
        class Work implements ModuleMigrationStrategy.Operations {
            boolean fail;
            public void migrate(ModuleMigrationStrategy.Prepared module){log.add("migrate:"+module.module());if(fail&&module.module().equals("dependent"))throw new IllegalStateException("fixture failure");}
            public void validate(ModuleMigrationStrategy.Prepared module){log.add("validate:"+module.module());}
            public void migrateApplication(){log.add("application");}
            public void validateApplication(SchemaDescription schema,Set<String> owned){log.add("validate:application");}
        }
        var operations=new Work();ModuleMigrationStrategy.coordinate(modules,EMPTY,Set.of(),operations);
        assertEquals(List.of("migrate:required","validate:required","migrate:dependent","validate:dependent","application","validate:application"),log);
        log.clear();operations.fail=true;assertThrows(IllegalStateException.class,()->ModuleMigrationStrategy.coordinate(modules,EMPTY,Set.of(),operations));
        assertEquals(List.of("migrate:required","validate:required","migrate:dependent"),log);
        log.clear();assertThrows(IllegalStateException.class,()->ModuleMigrationStrategy.coordinate(modules,EMPTY,Set.of("one/table"),operations));assertTrue(log.isEmpty());
    }
    public static class Probe {
        static final AtomicBoolean ready=new AtomicBoolean();
        public Probe(){if(!ready.get())throw new IllegalStateException("Function started before migrations");}
    }
    @Test void infrastructureGateCompletesInitializerBeforeModuleBeanConstruction() {
        Probe.ready.set(false);
        try(var context=new AnnotationConfigApplicationContext()) {
            var source=new SimpleDriverDataSource();context.registerBean("dataSource",javax.sql.DataSource.class,()->source);
            context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean("flywayInitializer",FlywayMigrationInitializer.class,()->new FlywayMigrationInitializer(Flyway.configure().load()){@Override public void afterPropertiesSet(){Probe.ready.set(true);}});
            var node=new ActivationPlan.Node("example.Probe","example.module","USE_CASE",Probe.class.getName(),List.of(),true);
            context.addBeanFactoryPostProcessor(new AdapterBindings(context.getEnvironment(),List.of(),Map.of(),List.of(node),List.of("example.module"),List.of(),Map.of(node.identity(),Probe.class),"fixture-schema"));
            context.refresh();assertNotNull(context.getBean("example.Probe"));assertTrue(Probe.ready.get());
        }
    }
    @Test void missingFlywayInitializerFailsBeforeAnyModuleFunctionStarts() {
        Probe.ready.set(false);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource",javax.sql.DataSource.class,SimpleDriverDataSource::new);
            var node=new ActivationPlan.Node("example.Probe","example.module","USE_CASE",Probe.class.getName(),List.of(),true);
            context.addBeanFactoryPostProcessor(new AdapterBindings(context.getEnvironment(),List.of(),Map.of(),List.of(node),List.of("example.module"),List.of(),Map.of(node.identity(),Probe.class),"fixture-schema"));
            assertThrows(RuntimeException.class,context::refresh);assertFalse(Probe.ready.get());
        }
    }
}
