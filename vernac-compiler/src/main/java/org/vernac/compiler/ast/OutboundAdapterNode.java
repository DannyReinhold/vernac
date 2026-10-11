package org.vernac.compiler.ast;
import java.util.List;
import java.util.Optional;
/** One complete port implementation; technical dependencies never become domain types. */
public record OutboundAdapterNode(SourceLocation location, String name, TypeNode portType,
        List<FieldNode> dependencies, List<FieldNode> javaDependencies,
        List<JavaImportNode> javaImports, List<ServiceMethodNode> methods, List<SchemaNode> schemas, List<MappingNode> mappings, Optional<RestAdapterNode> rest, List<AdapterConfigurationNode> configuration) implements TopLevelDefinition {
    public OutboundAdapterNode(SourceLocation location, String name, TypeNode portType,
            List<FieldNode> dependencies, List<FieldNode> javaDependencies, List<JavaImportNode> javaImports,
            List<ServiceMethodNode> methods, List<SchemaNode> schemas, List<MappingNode> mappings) {
        this(location, name, portType, dependencies, javaDependencies, javaImports, methods, schemas, mappings, Optional.empty(), List.of());
    }
    public DomainServiceNode behavior() {
        return new DomainServiceNode(location, name, dependencies, javaImports, methods);
    }
}
