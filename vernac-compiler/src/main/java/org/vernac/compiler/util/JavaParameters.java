// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.util;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.TypeName;

import org.jspecify.annotations.Nullable;

/**
 * Parameter nullability shared by generated APIs, implementation companions and editor contexts.
 */
public final class JavaParameters {
    private JavaParameters() {}

    public static TypeName type(TypeName raw, boolean optional) {
        return optional ? raw.annotated(AnnotationSpec.builder(Nullable.class).build()) : raw;
    }

    public static String sourceType(String raw, boolean optional) {
        if (!optional) return raw;
        int generic = raw.indexOf('<');
        int dot = raw.lastIndexOf('.', generic < 0 ? raw.length() : generic);
        return raw.substring(0, dot + 1)
                + "@org.jspecify.annotations.Nullable "
                + raw.substring(dot + 1);
    }
}
