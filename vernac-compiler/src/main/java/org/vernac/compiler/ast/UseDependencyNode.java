// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.Optional;

public record UseDependencyNode(
        SourceLocation location,
        String typeName,
        Optional<String> instanceName
) {
}