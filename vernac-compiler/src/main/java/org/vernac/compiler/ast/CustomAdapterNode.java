package org.vernac.compiler.ast;

import java.util.Optional;

public record CustomAdapterNode(
        Optional<String> customPackage, // NEU
        Optional<String> delegateName,
        Optional<String> inlineCode,
        SourceLocation location
) implements AdapterNode {
}
