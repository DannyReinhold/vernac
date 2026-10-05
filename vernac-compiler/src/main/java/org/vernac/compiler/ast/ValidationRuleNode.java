// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;

public record ValidationRuleNode(
        SourceLocation location,
        String condition,
        String message
) implements AstNode {}
