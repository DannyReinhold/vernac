// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Objects;

public record TypeNode(
        SourceLocation location,
        String name,
        List<TypeNode> typeArguments,
        boolean isOptional,
        PageKind pageKind
) implements AstNode {
    public TypeNode {
        Objects.requireNonNull(location);
        Objects.requireNonNull(name);
        typeArguments = List.copyOf(typeArguments);
        Objects.requireNonNull(pageKind);
    }

    public enum PageKind {
        NONE, OFFSET, COUNTED_OFFSET, CURSOR;
        public String javaName() { return switch(this) {
            case OFFSET -> "OffsetPage"; case COUNTED_OFFSET -> "CountedOffsetPage"; case CURSOR -> "CursorPage";
            case NONE -> throw new IllegalStateException("Not a page type");
        }; }
        public String syntax() { return switch(this) {
            case NONE -> ""; case OFFSET -> "offset"; case COUNTED_OFFSET -> "counted offset"; case CURSOR -> "cursor";
        }; }
    }
    public boolean paginated() { return pageKind != PageKind.NONE; }
    public TypeNode(SourceLocation location,String name,List<TypeNode> arguments,boolean optional) {
        this(location,name,arguments,optional,PageKind.NONE);
    }
    public TypeNode unpaged() { return new TypeNode(location,name,typeArguments,isOptional); }
    public TypeNode(SourceLocation location, String name) {
        this(location, name, List.of(), false);
    }
}