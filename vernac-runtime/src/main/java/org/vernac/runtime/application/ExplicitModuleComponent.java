// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.application;

import java.lang.annotation.*;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Preserves MVC annotations while excluding dependency components from ordinary scanning. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ExplicitModuleComponent.ExcludeFromScanning.class)
public @interface ExplicitModuleComponent {
    /** Explicit RootBeanDefinitions from the module registry do not evaluate scan conditions. */
    final class ExcludeFromScanning implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) { return false; }
    }
}
