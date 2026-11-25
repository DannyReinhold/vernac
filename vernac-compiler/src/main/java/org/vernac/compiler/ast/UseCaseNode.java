// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
import java.util.Optional;

/** One application operation with an explicit contract. */
public record UseCaseNode(SourceLocation location, String name,
        List<FieldNode> parameters, List<ValidationRuleNode> validations,
        List<FieldNode> injections, Optional<TypeNode> resultType, List<FieldNode> resultFields,
        List<JavaImportNode> javaImports, List<MethodNode> methods,
        String bodyCode, Optional<String> implementation, int executeCount, Optional<AuthorizationNode> authorization,
        List<NativeStatement> nativeBody) implements TopLevelDefinition {
    public UseCaseNode {
        nativeBody = List.copyOf(nativeBody);
    }

    public UseCaseNode(SourceLocation location, String name, List<FieldNode> parameters,
            List<ValidationRuleNode> validations, List<FieldNode> injections, Optional<TypeNode> resultType,
            List<FieldNode> resultFields, List<JavaImportNode> javaImports, List<MethodNode> methods,
            String bodyCode, Optional<String> implementation, int executeCount, Optional<AuthorizationNode> authorization) {
        this(location, name, parameters, validations, injections, resultType, resultFields, javaImports,
                methods, bodyCode, implementation, executeCount, authorization, List.of());
    }

    public UseCaseNode(SourceLocation location, String name, List<FieldNode> parameters,
            List<ValidationRuleNode> validations, List<FieldNode> injections, Optional<TypeNode> resultType,
            List<FieldNode> resultFields, List<JavaImportNode> javaImports, List<MethodNode> methods,
            String bodyCode, Optional<String> implementation, int executeCount) {
        this(location, name, parameters, validations, injections, resultType, resultFields,
                javaImports, methods, bodyCode, implementation, executeCount, Optional.empty());
    }
}
