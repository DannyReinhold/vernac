package org.vernac.compiler.ast;
import java.util.List;
public record PortMethodNode(String name, TypeNode returnType, List<FieldNode> parameters,
        SourceLocation location) implements AstNode {
    public MethodNode method() {
        return new MethodNode(location, "public", returnType, name, parameters, "");
    }
}
