// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import javax.lang.model.element.Modifier;
import org.jspecify.annotations.NullMarked;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.runtime.authorization.*;
import java.util.List;

/** Java authorization artifacts; consumes resolved permission identities, never parses expressions. */
public final class AuthorizationGenerator {
    private AuthorizationGenerator() {}
    static boolean guarded(UseCaseNode operation) {
        return operation.authorization().filter(a -> !(a instanceof AuthorizationNode.Mode mode) || mode.authenticated()).isPresent();
    }
    public static JavaFile permission(PermissionNode permission, String namespace) {
        var type = TypeSpec.enumBuilder(permission.name()).addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class)
                .addSuperinterface(PermissionKey.class).addJavadoc("Generated permission keys. Declaring a key grants no access.\n");
        permission.members().forEach(member -> type.addEnumConstant(member.name()));
        type.addMethod(MethodSpec.methodBuilder("qualifiedName").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .returns(String.class).addStatement("return $S + name()", namespace + "." + permission.name() + ".").build());
        return JavaFile.builder(namespace + ".authorization", type.build()).indent("    ").skipJavaLangImports(true).build();
    }
    static CodeBlock requirement(AuthorizationNode node, UseCaseNode operation, ResolvedProject project) {
        if (node instanceof AuthorizationNode.Mode mode)
            return CodeBlock.of("$T.Mode.$L", AuthorizationRequirement.class, mode.authenticated() ? "AUTHENTICATED" : "PUBLIC");
        if (node instanceof AuthorizationNode.Permission leaf) {
            var keyType = ResolvedJavaTypes.javaType(project.typeOf(leaf.type()));
            if (leaf.resource().isEmpty()) return CodeBlock.of("$T.permission($T.$N)", AuthorizationRequirement.class, keyType, leaf.member());
            var parameter = operation.parameters().stream().filter(p -> p.name().equals(leaf.resource().get())).findFirst().orElseThrow();
            return CodeBlock.of("$T.permission($T.$N, $T.of($T.class, $N))", AuthorizationRequirement.class,
                    keyType, leaf.member(), ResourceReference.class, ResolvedJavaTypes.javaType(project.typeOf(parameter.type())), parameter.name());
        }
        var group = (AuthorizationNode.Group) node;
        List<CodeBlock> children = group.children().stream().map(c -> requirement(c, operation, project)).toList();
        return CodeBlock.of("$T.$L($L)", AuthorizationRequirement.class, group.all() ? "all" : "any", CodeBlock.join(children, ", "));
    }
}
