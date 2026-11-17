// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
class IdentitySaasAutoConfigurationTest {
    @Test void libraryPresenceDoesNotActivateIdentityOrWorkers() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(IdentitySaasAutoConfiguration.class);context.refresh();
            assertTrue(context.getBeansOfType(IdentityJourneys.class).isEmpty());
            assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
        }
    }
    @Test void explicitActivationRequiresRealConfiguration() {
        try(var context=new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"vernac.identity.enabled=true");
            context.register(IdentitySaasAutoConfiguration.class);
            assertThrows(org.springframework.beans.BeansException.class,context::refresh);
        }
    }
}
