// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import javax.lang.model.element.Modifier;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;

/** Domain repository API, independent of SQL layout and schema planning. */
public final class RepositoryContractGenerator {
    public JavaFile generate(RepositoryNode repository, String namespace, AggregateNode aggregate,
            String aggregateNamespace, ResolvedProject project) {
        ClassName owner = ClassName.get(aggregateNamespace + ".domain", aggregate.name());
        TypeName id = ResolvedJavaTypes.javaType(project.typeOf(aggregate.idDefinition().type()));
        var contract = TypeSpec.interfaceBuilder(repository.name()).addModifiers(Modifier.PUBLIC)
                .addAnnotation(NullMarked.class);
        contract.addMethod(MethodSpec.methodBuilder("byId").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(owner).addParameter(id, "id").build());
        contract.addMethod(MethodSpec.methodBuilder("save").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(owner).addParameter(owner, "aggregate").build());
        contract.addMethod(MethodSpec.methodBuilder("delete").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter(owner, "aggregate").build());
        for (var method : repository.methods()) contract.addMethod(querySignature(method, owner, project));
        return JavaFile.builder(namespace + ".domain", contract.build())
                .indent("    ").skipJavaLangImports(true).build();
    }

    static MethodSpec querySignature(RepositoryMethodNode method, ClassName aggregate, ResolvedProject project) {
        var query = java.util.Objects.requireNonNull(project.queries().get(method), "Unresolved repository query");
        TypeName result = ResolvedJavaTypes.javaType(query.result());
        TypeName returns = query.singleton()
                ? ParameterizedTypeName.get(ClassName.get(Optional.class), result) : result;
        if (method.paginated()) returns = ParameterizedTypeName.get(
                ClassName.get("org.vernac.runtime.pagination",
                        method.cursor() ? "CursorQuery" : method.counting() ? "CountedOffsetQuery" : "OffsetQuery"),
                aggregate, result);
        var signature = MethodSpec.methodBuilder(method.name()).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(returns);
        for (var parameter : method.parameters()) signature.addParameter(
                ResolvedJavaTypes.javaType(project.typeOf(parameter.type())), parameter.name());
        return signature.build();
    }
}
