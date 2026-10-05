// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.Optional;

public record CustomAdapterNode(
        Optional<String> customPackage, // NEU
        Optional<String> delegateName,
        Optional<String> inlineCode,
        SourceLocation location
) implements AdapterNode {
}
