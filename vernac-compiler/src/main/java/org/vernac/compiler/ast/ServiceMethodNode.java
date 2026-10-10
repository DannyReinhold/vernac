// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.List;

public record ServiceMethodNode(MethodNode method, List<ValidationRuleNode> validations) { }
