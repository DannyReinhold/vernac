// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.Optional;

public sealed interface AdapterNode extends AstNode permits CustomAdapterNode, RestAdapterNode {
    Optional<String> customPackage(); // NEU

    SourceLocation location();
}

