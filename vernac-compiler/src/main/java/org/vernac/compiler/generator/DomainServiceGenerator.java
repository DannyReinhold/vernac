// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import java.util.*;
import javax.lang.model.element.Modifier;
import org.jspecify.annotations.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.util.ConditionUtils;
import org.vernac.runtime.*;

/** Stateless Spring domain service with an isolated implementation and scoped input views. */
public final class DomainServiceGenerator {
    public List<JavaFile> generate(DomainServiceNode service, CompilationUnitNode unit, ResolvedProject project) {
        ClassName owner = ClassName.get(unit.namespace() + ".domain", service.name());
        ClassName access = ClassName.get(owner.packageName() + ".access", service.name() + "Access");
        ClassName companion = BehaviorGenerator.companion(owner);
        var bean = TypeSpec.classBuilder(owner).addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class)
                .addAnnotation(ClassName.get("org.springframework.stereotype", "Service"))
                .addJavadoc("Generated domain service. Do not edit.\n");
        var api = TypeSpec.interfaceBuilder(access).addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class);
        var delegate = TypeSpec.classBuilder("__AccessView").addModifiers(Modifier.PRIVATE, Modifier.FINAL).addSuperinterface(access);
        var constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        for (var dependency : service.injections()) {
            var type = raw(dependency.type(), project);
            bean.addField(type, dependency.name(), Modifier.PRIVATE, Modifier.FINAL);
            constructor.addParameter(type, dependency.name()).addStatement("this.$N = $T.requireNonNull($N)", dependency.name(), Objects.class, dependency.name());
            api.addMethod(MethodSpec.methodBuilder(dependency.name()).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(type).build());
            delegate.addMethod(MethodSpec.methodBuilder(dependency.name()).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(type)
                    .addStatement("return $L.this.$N", owner.simpleName(), dependency.name()).build());
        }
        bean.addMethod(constructor.build());
        var impl = TypeSpec.classBuilder(companion).addModifiers(Modifier.FINAL).addAnnotation(NullMarked.class)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        String bodies = service.methods().stream().map(m -> m.method().bodyCode()).reduce("", (a, b) -> a + "\n" + b);
        new BehaviorGenerator().addBodyImports(impl, owner, service.javaImports(), unit, project, bodies);
        List<JavaFile> files = new ArrayList<>();
        int index = 0;
        for (var operation : service.methods()) {
            var method = operation.method();
            boolean exposed = method.accessModifier().equals("public");
            TypeName result = view(method.returnType(), project);
            var implementation = MethodSpec.methodBuilder(method.name()).addModifiers(Modifier.STATIC).returns(result);
            if (exposed) implementation.addParameter(access, "self");
            else implementation.addModifiers(Modifier.PRIVATE);
            for (var parameter : method.parameters()) implementation.addParameter(view(parameter.type(), project), parameter.name());
            if (method.implementation().isPresent()) {
                String arguments = "self" + (method.parameters().isEmpty() ? "" : ", " + names(method));
                implementation.addStatement((result.equals(TypeName.VOID) ? "" : "return ") + "$L.$N($L)",
                        method.implementation().get(), method.name(), arguments);
            } else implementation.addCode("$L\n", method.bodyCode());
            impl.addMethod(implementation.build());
            if (!exposed) { index++; continue; }
            var wrapper = signature(method, project).addModifiers(Modifier.PUBLIC);
            api.addMethod(signature(method, project).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).build());
            var forwarding = signature(method, project).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class);
            forwarding.addStatement((result.equals(TypeName.VOID) ? "" : "return ") + "$L.this.$N($L)", owner.simpleName(), method.name(), names(method));
            delegate.addMethod(forwarding.build());
            for (var parameter : method.parameters()) if (!parameter.type().isOptional() && !raw(parameter.type(), project).isPrimitive())
                wrapper.addStatement("$T.requireNonNull($N, $S)", DomainChecks.class, parameter.name(), service.name() + "." + method.name() + "." + parameter.name());
            if (!operation.validations().isEmpty()) {
                // Nested read interfaces avoid extra public top-level type names for overloaded methods.
                var read = access.nestedClass("Input" + index);
                var readSpec = TypeSpec.interfaceBuilder("Input" + index).addModifiers(Modifier.PUBLIC, Modifier.STATIC);
                var values = TypeSpec.anonymousClassBuilder("").addSuperinterface(read);
                for (var parameter : method.parameters()) {
                    readSpec.addMethod(MethodSpec.methodBuilder(parameter.name()).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(view(parameter.type(), project)).build());
                    values.addMethod(MethodSpec.methodBuilder(parameter.name()).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(view(parameter.type(), project))
                            .addStatement("return $L", argument(parameter)).build());
                }
                api.addType(readSpec.build());
                ClassName validator = ClassName.get(owner.packageName(), "__VernacValidation_" + service.name());
                wrapper.addStatement("$T.validate$L($L)", validator, index, values.build());
            }
            List<CodeBlock> arguments = new ArrayList<>(); arguments.add(CodeBlock.of("new __AccessView()"));
            for (var parameter : method.parameters()) arguments.add(argument(parameter));
            var invocation = CodeBlock.of("$T.$N($L)", companion, method.name(), CodeBlock.join(arguments, ", "));
            if (result.equals(TypeName.VOID)) wrapper.addStatement("$L", invocation);
            else if (result.isPrimitive()) wrapper.addStatement("return $L", invocation);
            else wrapper.addStatement("return $T.requireResult($L, $S)", BehaviorContractException.class, invocation, owner.canonicalName() + "." + method.name());
            bean.addMethod(wrapper.build()); index++;
        }
        var validation = TypeSpec.classBuilder("__VernacValidation_" + service.name()).addModifiers(Modifier.FINAL).addAnnotation(NullMarked.class)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        boolean hasValidation = false;
        for (int i = 0; i < service.methods().size(); i++) {
            var operation = service.methods().get(i);
            if (operation.validations().isEmpty()) continue;
            hasValidation = true;
            var validate = MethodSpec.methodBuilder("validate" + i).addModifiers(Modifier.STATIC).addParameter(access.nestedClass("Input" + i), "self");
            for (var rule : operation.validations()) validate.beginControlFlow("if ($L)", ConditionUtils.negate(rule.condition()))
                    .addStatement("throw new $T($S)", DomainValidationException.class, rule.message().isBlank() ? service.name() + "." + operation.method().name() + ": precondition failed" : rule.message()).endControlFlow();
            validation.addMethod(validate.build());
        }
        if (hasValidation) {
            new BehaviorGenerator().addBodyImports(validation, owner, service.javaImports(), unit, project, service.methods().toString());
            files.add(file(owner.packageName(), validation));
        }
        bean.addType(delegate.build());
        files.add(file(owner.packageName(), bean)); files.add(file(owner.packageName(), impl)); files.add(file(access.packageName(), api));
        return files;
    }
    private MethodSpec.Builder signature(MethodNode method, ResolvedProject project) {
        var result = MethodSpec.methodBuilder(method.name()).returns(view(method.returnType(), project));
        for (var parameter : method.parameters()) {
            TypeName type = raw(parameter.type(), project);
            if (parameter.type().isOptional()) type = type.annotated(AnnotationSpec.builder(Nullable.class).build());
            result.addParameter(type, parameter.name());
        }
        return result;
    }
    private static String names(MethodNode method) { return String.join(", ", method.parameters().stream().map(FieldNode::name).toList()); }
    private static CodeBlock argument(FieldNode parameter) {
        return parameter.type().isOptional() ? CodeBlock.of("$T.ofNullable($N)", Optional.class, parameter.name()) : CodeBlock.of("$N", parameter.name());
    }
    private static TypeName view(TypeNode type, ResolvedProject project) {
        var raw = raw(type, project);
        return type.isOptional() ? ParameterizedTypeName.get(ClassName.get(Optional.class), raw.box()) : raw;
    }
    private static TypeName raw(TypeNode type, ResolvedProject project) {
        var resolved = project.typeOf(type);
        if (resolved instanceof ResolvedType.Declared d) return ClassName.get(d.symbol().identity().namespace() + ".domain", d.symbol().identity().name());
        return ResolvedJavaTypes.javaType(resolved);
    }
    private static JavaFile file(String pkg, TypeSpec.Builder type) { return JavaFile.builder(pkg, type.build()).indent("    ").skipJavaLangImports(true).build(); }
}
