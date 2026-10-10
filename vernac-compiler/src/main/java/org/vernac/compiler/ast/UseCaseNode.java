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
        String bodyCode, Optional<String> implementation, int executeCount) implements TopLevelDefinition {
}
