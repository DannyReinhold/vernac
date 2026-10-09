// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.runtime.BehaviorContractException;
import javax.lang.model.element.Modifier;
import java.util.*;
import java.nio.file.Path;

/** Isolates inline Java in a separate top-level class without private domain access. */
public final class BehaviorGenerator {
    public static ClassName receiver(ClassName owner, MethodNode method) {
        return method.mode() == MethodNode.Mode.DEFAULT ? owner : ClassName.get(owner.packageName() + ".access",
                owner.simpleName() + (method.mode() == MethodNode.Mode.READ ? "Read" : "Access"));
    }

    public static ClassName companion(ClassName owner) {
        return ClassName.get(owner.packageName(), "__VernacBehavior_" + owner.simpleName());
    }

    public MethodSpec delegate(ClassName owner, MethodNode method, ResolvedProject project) {
        MethodSpec.Builder wrapper = signature(method, project).addModifiers(Modifier.PUBLIC);
        for (FieldNode parameter : method.parameters()) {
            if (!parameter.type().isOptional() && !ResolvedJavaTypes.javaType(project.typeOf(parameter.type())).isPrimitive())
                wrapper.addStatement("$T.requireNonNull($L, $S)", Objects.class, parameter.name(),
                        owner.simpleName() + "." + method.name() + ": parameter '" + parameter.name() + "' must not be null");
        }
        // The local bridge pins the argument and result types even for external implementations.
        String args = arguments(method, "this");
        boolean nothing = method.returnType().name().equals("void");
        TypeName result = returnType(method, project);
        CodeBlock call = CodeBlock.of("$T.$L($L)", companion(owner), method.name(), args);
        if (nothing) wrapper.addStatement("$L", call);
        else if (result.isPrimitive()) wrapper.addStatement("return $L", call);
        else wrapper.addStatement("return $T.requireResult($L, $S)", BehaviorContractException.class, call,
                owner.canonicalName() + "." + method.name() + " (" + sourceLocation(method, project) + ")");
        return wrapper.build();
    }

    public Optional<JavaFile> generate(ClassName owner, List<MethodNode> methods, List<JavaImportNode> imports,
                                       CompilationUnitNode unit, ResolvedProject project) {
        if (methods.isEmpty()) return Optional.empty();
        var type = TypeSpec.classBuilder(companion(owner)).addModifiers(Modifier.FINAL).addAnnotation(NullMarked.class)
                .addJavadoc("Generated behavior implementation for $T. Do not edit.\n", owner)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        addBodyImports(type, owner, imports, unit, project,
                methods.stream().map(MethodNode::bodyCode).reduce("", (a, b) -> a + "\n" + b));
        for (var method : methods) {
            MethodSpec.Builder implementation = MethodSpec.methodBuilder(method.name()).returns(returnType(method, project))
                    .addModifiers(Modifier.STATIC)
                    .addJavadoc("Vernac source: $L\n", sourceLocation(method, project).replace("*/", "* /"));
            if (method.accessModifier().equals("private")) implementation.addModifiers(Modifier.PRIVATE);
            else implementation.addParameter(receiver(owner, method), "self");
            addParameters(implementation, method, project);
            if (method.implementation().isPresent()) {
                // Use a literal qualified target to keep Java imports out of delegation resolution.
                String invocation = method.implementation().get() + "." + method.name() + "(" + arguments(method, "self") + ")";
                implementation.addStatement(method.returnType().name().equals("void") ? "$L" : "return $L", invocation);
            } else implementation.addCode("$L\n", method.bodyCode());
            type.addMethod(implementation.build());
        }
        return Optional.of(JavaFile.builder(owner.packageName(), type.build()).indent("    ").skipJavaLangImports(true).build());
    }

    public void addBodyImports(TypeSpec.Builder type, ClassName owner, List<JavaImportNode> imports,
                               CompilationUnitNode unit, ResolvedProject project, String bodies) {
        var source = project.project().sources().stream().filter(s -> s.unit() == unit).findFirst().orElseThrow();
        var scope = project.scopes().get(source.path());
        Map<String, String> visible = new TreeMap<>(BehaviorImports.visible(project.project(), scope));
        for (var imported : imports) visible.put(simpleName(imported.target()), imported.target());
        // JavaPoet tracks these type references, so body-only imports are preserved as well.
        // @see documents the Java context without adding artificial fields or methods.
        for (var entry : visible.entrySet()) {
            String name = entry.getKey();
            if (entry.getValue().contains(".") && java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_$])"
                    + java.util.regex.Pattern.quote(name) + "(?![\\p{L}\\p{N}_$])").matcher(bodies).find())
                type.addJavadoc("@see $T\n", ClassName.get(entry.getValue().substring(0, entry.getValue().lastIndexOf('.')), name));
        }
    }

    /** Portable display location only; diagnostic/LSP source identities remain unchanged. */
    private String sourceLocation(MethodNode method, ResolvedProject project) {
        var location = method.location();
        String source = location.sourceName();
        if (!(source.startsWith("<") && source.endsWith(">"))) {
            Path path = Path.of(source).toAbsolutePath().normalize();
            Path root = project.project().sourceRoot();
            // Standalone compilation may use a source outside the project root.
            source = (path.startsWith(root) ? root.relativize(path) : path.getFileName())
                    .toString().replace('\\', '/');
        }
        return source + ":" + location.line() + ":" + location.column();
    }

    private String simpleName(String target) { return target.substring(target.lastIndexOf('.') + 1); }
    private String arguments(MethodNode method, String receiver) {
        return receiver + (method.parameters().isEmpty() ? "" : ", " + String.join(", ", method.parameters().stream().map(FieldNode::name).toList()));
    }
    public TypeName returnType(MethodNode method, ResolvedProject project) {
        TypeName type = ResolvedJavaTypes.javaType(project.typeOf(method.returnType()));
        return method.returnType().isOptional() ? ParameterizedTypeName.get(ClassName.get(Optional.class), type) : type;
    }
    public MethodSpec.Builder signature(MethodNode method, ResolvedProject project) {
        var builder = MethodSpec.methodBuilder(method.name()).returns(returnType(method, project));
        addParameters(builder, method, project);
        return builder;
    }
    private void addParameters(MethodSpec.Builder builder, MethodNode method, ResolvedProject project) {
        for (var parameter : method.parameters()) {
            TypeName type = ResolvedJavaTypes.javaType(project.typeOf(parameter.type()));
            if (parameter.type().isOptional()) type = type.annotated(AnnotationSpec.builder(Nullable.class).build());
            builder.addParameter(type, parameter.name());
        }
    }
}
