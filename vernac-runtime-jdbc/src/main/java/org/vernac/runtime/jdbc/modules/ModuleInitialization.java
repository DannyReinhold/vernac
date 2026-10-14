// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.modules;
import org.springframework.beans.factory.*;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.transaction.PlatformTransactionManager;
/** Forces Boot's complete migration initializer before dependent module components are constructed. */
public final class ModuleInitialization implements InitializingBean {
    private final ObjectProvider<FlywayMigrationInitializer> initializers;
    private final ObjectProvider<PlatformTransactionManager> managers;
    public ModuleInitialization(ObjectProvider<FlywayMigrationInitializer> initializers,ObjectProvider<PlatformTransactionManager> managers){this.initializers=initializers;this.managers=managers;}
    @Override public void afterPropertiesSet() {
        try {initializers.getObject();managers.getObject();}
        catch(NoSuchBeanDefinitionException e){throw new IllegalStateException("Persistent module activation requires enabled Spring Boot Flyway initialization and an application transaction manager.",e);}
    }
}
