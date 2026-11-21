// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.inbound;

import java.lang.annotation.*;

/** Declared servlet inputs of a generated MVC endpoint, available to application policies. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MvcFormContract {
    String[] form() default {};
    String[] query() default {};
    String[] canonicalUuids() default {};
}
