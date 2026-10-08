// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.runtime.BehaviorContractException;
import org.vernac.runtime.BehaviorModification;
import javax.lang.model.element.Modifier;
import java.util.*;

/** Generates public contracts and private forwarding views; owners never implement these contracts. */
public final class DomainAccessGenerator {
    private final BehaviorGenerator behavior = new BehaviorGenerator();

    public List<JavaFile> interfaces(MutableDomain model, ClassName owner, ResolvedProject project) {
        var read = TypeSpec.interfaceBuilder(owner.simpleName() + "Read").addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class);
        var write = TypeSpec.interfaceBuilder(owner.simpleName() + "Write").addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class);
        read.addMethod(abstractMethod(getter("id", ResolvedJavaTypes.javaType(project.typeOf(model.id().type())), false)));
        for (var field : model.fields()) {
            read.addMethod(abstractMethod(getter(field.name(), type(field, project), field.type().isOptional())));
            if (field.isMutable()) write.addMethod(abstractMethod(setter(field, project)));
        }
        if (model.aggregate()) for (var name : List.of("createdAt", "updatedAt", "version"))
            read.addMethod(abstractMethod(getter(name, name.equals("version") ? TypeName.LONG : ClassName.get(java.time.Instant.class), false)));
        for (var method : model.methods()) if (method.accessModifier().equals("public"))
            (method.mode() == MethodNode.Mode.READ ? read : write).addMethod(abstractMethod(behavior.signature(method, project)));
        var access = TypeSpec.interfaceBuilder(owner.simpleName() + "Access").addModifiers(Modifier.PUBLIC)
                .addAnnotation(NullMarked.class).addSuperinterface(view(owner, "Read")).addSuperinterface(view(owner, "Write"));
        return List.of(file(owner, read.build()), file(owner, write.build()), file(owner, access.build()));
    }

    public void addTo(TypeSpec.Builder entity, MutableDomain model, ClassName owner, ResolvedProject project) {
        var read = TypeSpec.classBuilder("__ReadView").addModifiers(Modifier.PRIVATE)
                .addSuperinterface(view(owner, "Read"));
        var access = TypeSpec.classBuilder("__AccessView").addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .superclass(ClassName.get("", "__ReadView")).addSuperinterface(view(owner, "Access"))
                .addField(FieldSpec.builder(Runnable.class, "guard", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer("$T.accessGuard()", BehaviorModification.class).build());
        forwardGetter(read, getter("id", ResolvedJavaTypes.javaType(project.typeOf(model.id().type())), false), owner, "id");
        for (var field : model.fields()) {
            forwardGetter(read, getter(field.name(), type(field, project), field.type().isOptional()), owner, field.name());
            if (field.isMutable()) {
                var setter = setter(field, project).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                        .addStatement("this.guard.run()");
                if (!field.type().isOptional() && !type(field, project).isPrimitive())
                    setter.addStatement("$T.requireNonNull($N, $S)", Objects.class, field.name(), field.name() + " must not be null");
                setter.beginControlFlow("if (!$T.equals($L.this.$N, $N))", Objects.class, owner.simpleName(), field.name(), field.name())
                        .addStatement("$L.this.$N = $N", owner.simpleName(), field.name(), field.name());
                if (model.aggregate()) setter.addStatement("$L.this.markAsUpdated()", owner.simpleName());
                setter.endControlFlow();
                access.addMethod(setter.build());
            }
        }
        if (model.aggregate()) for (String name : List.of("createdAt", "updatedAt", "version"))
            forwardGetter(read, getter(name, name.equals("version") ? TypeName.LONG : ClassName.get(java.time.Instant.class), false), owner, name);
        for (var method : model.methods()) {
            if (!method.accessModifier().equals("public")) continue;
            var forward = behavior.signature(method, project).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class);
            boolean modifying = method.mode() == MethodNode.Mode.MODIFY;
            if (modifying) forward.addStatement("this.guard.run()");
            forward.addStatement((method.returnType().name().equals("void") ? "" : "return ") + "$L.this.$N($L)",
                    owner.simpleName(), method.name(), arguments(method));
            (modifying ? access : read).addMethod(forward.build());
            entity.addMethod(wrapper(method, owner, project));
        }
        entity.addType(read.build()).addType(access.build());
        var optional = model.fields().stream().filter(f -> f.type().isOptional()).toList();
        if (!optional.isEmpty()) {
            var factory = MethodSpec.methodBuilder("create").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(owner);
            var args = new ArrayList<String>();
            for (var field : model.fields()) {
                if (field.type().isOptional()) args.add("null");
                else { factory.addParameter(type(field, project), field.name()); args.add(field.name()); }
            }
            factory.addStatement("return create($L)", String.join(", ", args));
            entity.addMethod(factory.build());
        }
    }

    private MethodSpec wrapper(MethodNode method, ClassName owner, ResolvedProject project) {
        var wrapper = behavior.signature(method, project).addModifiers(Modifier.PUBLIC);
        for (var p : method.parameters()) if (!p.type().isOptional() && !type(p, project).isPrimitive())
            wrapper.addStatement("$T.requireNonNull($N, $S)", Objects.class, p.name(), p.name() + " must not be null");
        boolean modify = method.mode() == MethodNode.Mode.MODIFY;
        boolean nothing = method.returnType().name().equals("void");
        if (modify) wrapper.addCode((nothing ? "" : "return ") + "$T.$L(this, this::validate, () -> {\n$>",
                BehaviorModification.class, nothing ? "run" : "execute");
        var call = CodeBlock.of("$T.$N(new $L(), $L)", BehaviorGenerator.companion(owner), method.name(),
                modify ? "__AccessView" : "__ReadView", arguments(method));
        // Avoid a trailing comma on parameterless methods.
        if (method.parameters().isEmpty()) call = CodeBlock.of("$T.$N(new $L())", BehaviorGenerator.companion(owner), method.name(), modify ? "__AccessView" : "__ReadView");
        if (nothing) wrapper.addStatement("$L", call);
        else if (behavior.returnType(method, project).isPrimitive()) wrapper.addStatement("return $L", call);
        else wrapper.addStatement("return $T.requireResult($L, $S)", BehaviorContractException.class, call, owner.canonicalName() + "." + method.name());
        if (modify) wrapper.addCode("$<});\n");
        return wrapper.build();
    }
    private static String arguments(MethodNode method) { return String.join(", ", method.parameters().stream().map(FieldNode::name).toList()); }
    private static ClassName view(ClassName owner, String suffix) { return ClassName.get(owner.packageName(), owner.simpleName() + suffix); }
    private static TypeName type(FieldNode field, ResolvedProject project) { return ResolvedJavaTypes.javaType(project.typeOf(field.type())); }
    private static JavaFile file(ClassName owner, TypeSpec type) { return JavaFile.builder(owner.packageName(), type).indent("    ").skipJavaLangImports(true).build(); }
    private static MethodSpec abstractMethod(MethodSpec.Builder method) { return method.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).build(); }
    private static MethodSpec.Builder getter(String name, TypeName type, boolean optional) {
        return MethodSpec.methodBuilder(name).returns(optional ? ParameterizedTypeName.get(ClassName.get(Optional.class), type) : type);
    }
    private static MethodSpec.Builder setter(FieldNode field, ResolvedProject project) {
        var parameter = ParameterSpec.builder(type(field, project), field.name());
        if (field.type().isOptional()) parameter.addAnnotation(Nullable.class);
        return MethodSpec.methodBuilder(field.name()).addParameter(parameter.build());
    }
    private static void forwardGetter(TypeSpec.Builder view, MethodSpec.Builder method, ClassName owner, String name) {
        view.addMethod(method.addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .addStatement("return $L.this.$N()", owner.simpleName(), name).build());
    }
}
