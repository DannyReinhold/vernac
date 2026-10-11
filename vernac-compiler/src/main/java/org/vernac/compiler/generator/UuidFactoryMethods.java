// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import org.jspecify.annotations.Nullable;
import org.vernac.runtime.DomainValidationException;
import javax.lang.model.element.Modifier;
import java.util.UUID;

/** Shared UUID parsing factory for identifiers and UUID-based value objects. */
final class UuidFactoryMethods {
    private UuidFactoryMethods() { }

    static MethodSpec fromString(ClassName self, String fieldName, boolean optional) {
        TypeName parameterType = ClassName.get(String.class);
        if (optional) parameterType = parameterType.annotated(AnnotationSpec.builder(Nullable.class).build());
        var parameter = ParameterSpec.builder(parameterType, "value");
        var method = MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(self).addParameter(parameter.build())
                .beginControlFlow("if (value == null)");
        if (optional) method.addStatement("return new $T(($T) null)", self, UUID.class);
        else method.addStatement("throw new $T($S)", DomainValidationException.class,
                self.simpleName() + "." + fieldName + " must not be null");
        method.endControlFlow().addStatement("$T parsed", UUID.class)
                .beginControlFlow("try").addStatement("parsed = $T.fromString(value)", UUID.class)
                .nextControlFlow("catch ($T cause)", IllegalArgumentException.class)
                .addStatement("throw new $T($S, cause)", DomainValidationException.class,
                        self.simpleName() + "." + fieldName + " must be a valid UUID")
                .endControlFlow().addStatement("return new $T(parsed)", self);
        return method.build();
    }
}
