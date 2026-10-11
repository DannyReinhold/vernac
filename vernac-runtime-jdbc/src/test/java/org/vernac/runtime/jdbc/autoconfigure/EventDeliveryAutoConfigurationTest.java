// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.autoconfigure;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.runtime.*;
import org.vernac.runtime.events.*;
import org.vernac.runtime.jdbc.JdbcEventWorker;
import static org.junit.jupiter.api.Assertions.*;

class EventDeliveryAutoConfigurationTest {
    record Ping(EventId eventId,Instant occurredAt) implements RecordedEvent {}
    private AnnotationConfigApplicationContext context(boolean enabled) {
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("vernac.events.enabled",Boolean.toString(enabled))));
        var data=new DriverManagerDataSource();
        context.registerBean("jdbc",NamedParameterJdbcTemplate.class,()->new NamedParameterJdbcTemplate(data));
        context.registerBean("transactions",PlatformTransactionManager.class,()->new DataSourceTransactionManager(data));
        context.registerBean("storage",EventStorage.class,()->new EventStorage(){
            public String eventType(){return "test.Ping";}
            public String listenerId(){return "@storage:test.Ping";}
            public Class<? extends DomainEvent> eventClass(){return Ping.class;}
            public void persist(DomainEvent event,List<String> listeners){fail("Unexpected storage access");}
            public DomainEvent load(EventId id){throw new AssertionError("Unexpected storage access");}
        });
        context.register(EventDeliveryAutoConfiguration.class);
        context.refresh();return context;
    }
    @Test void recordedOnlyDoesNotStartPollingOrConnectToDatabase() {
        try(var context=context(true)) {
            assertNotNull(context.getBean(JdbcEventWorker.class));
            assertFalse(context.getBean("vernacEventPolling",SmartLifecycle.class).isRunning());
        }
    }
    @Test void disabledWorkerLeavesStorageAvailable() {
        try(var context=context(false)) {
            assertTrue(context.getBeansOfType(JdbcEventWorker.class).isEmpty());
            assertEquals(1,context.getBeansOfType(EventStorage.class).size());
        }
    }
}
