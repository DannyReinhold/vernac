package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record RestAdapterNode(
        Optional<String> customPackage, // NEU
        List<RestConfigNode> configs,
        List<RestErrorRuleNode> errorRules,
        SourceLocation location
) implements AdapterNode {
}
