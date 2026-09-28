package org.vernac.compiler.ast;

public sealed interface AstNode permits CompilationUnitNode, ValueObjectNode, FieldNode, TypeNode {
    SourceLocation location();
}