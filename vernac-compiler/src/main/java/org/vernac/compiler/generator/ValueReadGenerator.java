// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import org.jspecify.annotations.NullMarked;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
import javax.lang.model.element.Modifier;
import java.util.*;

/** Read-only validation contract for a value object that declares state invariants. */
public final class ValueReadGenerator {
    private List<MethodSpec> signatures(ValueObjectNode value, ResolvedProject project) {
        var methods = new ArrayList<MethodSpec>();
        for (var field : value.fields()) {
            TypeName type = ResolvedJavaTypes.javaType(project.typeOf(field.type()));
            if (field.type().isOptional()) type = ParameterizedTypeName.get(ClassName.get(Optional.class), type);
            methods.add(MethodSpec.methodBuilder(field.name()).returns(type).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).build());
        }
        for (var method : value.methods()) if (method.accessModifier().equals("public"))
            methods.add(new BehaviorGenerator().signature(method, project).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).build());
        return methods;
    }
    public JavaFile generate(ValueObjectNode value, ClassName owner, ResolvedProject project) {
        var read = TypeSpec.interfaceBuilder(owner.simpleName() + "Read").addModifiers(Modifier.PUBLIC)
                .addAnnotation(NullMarked.class).addMethods(signatures(value, project));
        return JavaFile.builder(owner.packageName() + ".access", read.build()).indent("    ").skipJavaLangImports(true).build();
    }
    public void addView(TypeSpec.Builder ownerType, ValueObjectNode value, ClassName owner, ResolvedProject project) {
        var view = TypeSpec.classBuilder("__ReadView").addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .addSuperinterface(ClassName.get(owner.packageName() + ".access", owner.simpleName() + "Read"));
        for (var method : signatures(value, project)) {
            var forward = MethodSpec.methodBuilder(method.name()).returns(method.returnType())
                    .addModifiers(Modifier.PUBLIC).addParameters(method.parameters());
            forward.addAnnotation(Override.class).addStatement((method.returnType().equals(TypeName.VOID) ? "" : "return ") + "$L.this.$N($L)",
                    owner.simpleName(), method.name(), String.join(", ", method.parameters().stream().map(ParameterSpec::name).toList()));
            view.addMethod(forward.build());
        }
        ownerType.addType(view.build());
    }
}
