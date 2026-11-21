// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class UseCaseAccessAuditTest {
    public static class Operation {
        static final AtomicInteger created=new AtomicInteger();
        public Operation(){created.incrementAndGet();}
    }
    public static class Inactive {}
    @Test void missingClausesAbortStartupBeforeLazyUsecasesOrPoliciesCanRun() {
        Operation.created.set(0);
        try(var context=new GenericApplicationContext()) {
            context.registerBean("operation",Operation.class,d->d.setLazyInit(true));
            context.addBeanFactoryPostProcessor(factory->UseCaseAccessAudit.verify(factory,List.of(new UseCaseAccessAudit.Entry("sample.Operation",Operation.class,"OMITTED"))));
            var error=assertThrows(IllegalStateException.class,context::refresh);
            assertTrue(error.getMessage().contains("sample.Operation"));assertTrue(error.getMessage().contains("requires public"));
            assertEquals(0,Operation.created.get());
        }
    }
    @Test void explicitModesAreInspectionOnlyAndInactiveModuleEntriesDoNotRequireAccess() {
        for(String mode:List.of("PUBLIC","AUTHENTICATED","PERMISSION","ALL","ANY"))try(var context=new GenericApplicationContext()) {
            Operation.created.set(0);context.registerBean("operation",Operation.class,d->d.setLazyInit(true));
            context.addBeanFactoryPostProcessor(factory->UseCaseAccessAudit.verify(factory,List.of(
                new UseCaseAccessAudit.Entry("sample.Operation",Operation.class,mode),new UseCaseAccessAudit.Entry("inactive.Private",Inactive.class,"OMITTED"))));
            context.refresh();assertEquals(0,Operation.created.get());
        }
    }
    public static class OpaqueFactory implements org.springframework.beans.factory.FactoryBean<Object> {
        public Object getObject(){return new Operation();}
        public Class<?> getObjectType(){return null;}
        public boolean isSingleton(){return true;}
    }
    @Test void opaqueLazyFactoryProductsAreRejectedWhenTheyBecomeAvailable() {
        try(var context=new GenericApplicationContext()) {
            context.registerBean("opaque",OpaqueFactory.class,d->d.setLazyInit(true));
            context.addBeanFactoryPostProcessor(factory->UseCaseAccessAudit.verify(factory,List.of(new UseCaseAccessAudit.Entry("sample.Operation",Operation.class,"OMITTED"))));
            context.refresh();var error=assertThrows(RuntimeException.class,()->context.getBean("opaque"));
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            assertInstanceOf(IllegalStateException.class,cause);assertTrue(cause.getMessage().contains("sample.Operation"));
        }
    }
    @Test void reportsEveryMissingOperationInStableOrderAndRejectsCorruptInventory() {
        try(var context=new GenericApplicationContext()) {
            context.registerBean("operation",Operation.class);
            var entries=List.of(new UseCaseAccessAudit.Entry("sample.Z",Operation.class,"OMITTED"),new UseCaseAccessAudit.Entry("sample.A",Operation.class,"OMITTED"));
            var error=assertThrows(IllegalStateException.class,()->UseCaseAccessAudit.verify(context.getDefaultListableBeanFactory(),entries));
            assertTrue(error.getMessage().contains("sample.A, sample.Z"));
            assertThrows(IllegalStateException.class,()->UseCaseAccessAudit.verify(context.getDefaultListableBeanFactory(),List.of(entries.getFirst(),entries.getFirst())));
            assertThrows(IllegalArgumentException.class,()->new UseCaseAccessAudit.Entry("sample.X",Operation.class,"ALLOW"));
        }
    }
}
