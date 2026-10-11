package org.vernac.compiler.ast;
import java.util.List;
/** Transport-independent domain capability. */
public record PortNode(String name, List<PortMethodNode> methods, SourceLocation location)
        implements TopLevelDefinition { }
