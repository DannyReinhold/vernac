// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;

import org.vernac.compiler.ast.InboundHttpAdapterNode;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.symbols.ResolvedType;

/** One selector implementation for JSON and HTML transport policies. */
final class InboundErrorConditions {
    private InboundErrorConditions() {}

    static CodeBlock condition(InboundHttpAdapterNode.ErrorRule rule, ResolvedProject project) {
        var errors = ClassName.get("org.vernac.runtime.http.inbound", "InboundHttpErrors");
        if (rule.selector().equals("rejection")) {
            var type = (ResolvedType.Declared) project.typeOf(rule.rejectionType().orElseThrow());
            var id = type.symbol().identity();
            return CodeBlock.of(
                    "$T.rejection(error, $T.$L)",
                    errors,
                    ClassName.get(id.namespace() + ".domain", id.name()),
                    rule.constant());
        }
        if (rule.selector().equals("integration"))
            return CodeBlock.of(
                    "$T.integration(error, $S, $T.$L)",
                    errors,
                    rule.responsibility(),
                    ClassName.get("org.vernac.runtime.ports", "IntegrationCategory"),
                    rule.category());
        return CodeBlock.of("$T.matches(error, $S)", errors, rule.selector());
    }
}
