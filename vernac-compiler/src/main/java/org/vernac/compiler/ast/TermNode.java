// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;

/** Vocabulary is metadata, not an executable type declaration. */
public record TermNode(SourceLocation location,String name,String description,List<String> examples,List<String> notes) implements AstNode {
    public TermNode {examples=List.copyOf(examples);notes=List.copyOf(notes);}
}
