package org.vernac.compiler.ast;
import java.util.List;
/** One complete port implementation; technical dependencies never become domain types. */
public record OutboundAdapterNode(SourceLocation location, String name, TypeNode portType,
        List<FieldNode> dependencies, List<FieldNode> javaDependencies,
        List<JavaImportNode> javaImports, List<ServiceMethodNode> methods, List<SchemaNode> schemas, List<MappingNode> mappings) implements TopLevelDefinition {
    public DomainServiceNode behavior() {
        return new DomainServiceNode(location, name, dependencies, javaImports, methods);
    }
}
