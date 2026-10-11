package org.vernac.compiler.ast;

/** A required, typed property; independent of the domain type system. */
public record AdapterConfigurationNode(
    SourceLocation location, String type, String name, String property) {}
