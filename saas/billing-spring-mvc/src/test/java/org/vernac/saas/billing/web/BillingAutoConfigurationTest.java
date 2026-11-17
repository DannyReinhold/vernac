// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
class BillingAutoConfigurationTest {
    @Test void libraryPresenceDoesNotInstallBillingRoutesOrWorkers() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(BillingAutoConfiguration.class);context.refresh();
            assertTrue(context.getBeansOfType(BillingController.class).isEmpty());
            assertTrue(context.getBeansOfType(org.vernac.saas.billing.lifecycle.BillingMaintenance.class).isEmpty());
        }
    }
    @Test void explicitActivationRequiresApplicationPolicy() {
        try(var context=new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"vernac.billing.enabled=true");
            context.register(BillingAutoConfiguration.class);
            assertThrows(org.springframework.beans.BeansException.class,context::refresh);
        }
    }
}
