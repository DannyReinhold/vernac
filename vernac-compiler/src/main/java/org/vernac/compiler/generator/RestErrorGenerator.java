// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import org.vernac.compiler.rest.ResolvedRestOperation.*;

/** Emits only resolved rules; raw DSL expressions are never copied into Java. */
final class RestErrorGenerator {
    private static final ClassName CONDITION = ClassName.get("org.vernac.runtime.http", "ErrorCondition");
    private static final ClassName VALUES = ClassName.get("org.vernac.runtime.http", "ErrorValues");
    CodeBlock handler(Errors plan) {
        var code = CodeBlock.builder().add("(__invocation, __response) -> {\n").indent();
        if (plan.dto() != null) code.addStatement("var __error = $T.errorBody(__response, $T.class)",
                ClassName.get("org.vernac.runtime.http", "HttpJson"), plan.dto().javaType());
        for (var rule : plan.rules()) {
            code.beginControlFlow("if (($L).matches())", condition(rule.condition()));
            var a = rule.action();
            if (a.kind().equals("reject")) code.addStatement("var __failure = new $T(__invocation, $T.$N, $S, __response.failureDetails(), null)",
                    ClassName.get("org.vernac.runtime.ports", "PortRejectedException"), rule.codeType().javaType(), rule.code(), a.message());
            else {
                String type = switch (a.kind()) { case "local" -> "LocalIntegrationException"; case "remote" -> "RemoteIntegrationException"; default -> "PortInvocationException"; };
                code.addStatement("var __failure = new $T(__invocation, $T.$N, $S, __response.failureDetails(), null)",
                    ClassName.get("org.vernac.runtime.ports", type), ClassName.get("org.vernac.runtime.ports", "IntegrationCategory"), rule.code(), a.message());
            }
            finish(code, plan); code.endControlFlow();
        }
        code.addStatement("var __failure = $T.defaultFailure(__invocation, __response)", ClassName.get("org.vernac.runtime.http", "HttpFailures"));
        finish(code, plan);
        return code.unindent().add("}").build();
    }
    private void finish(CodeBlock.Builder code, Errors plan) {
        if (plan.dto() != null) code.addStatement("__error.decodingFailure().ifPresent(__failure::addSuppressed)");
        code.addStatement("return __failure");
    }
    private CodeBlock condition(Condition c) {
        if (c instanceof Negation n) return CodeBlock.of("($L).not()", condition(n.operand()));
        if (c instanceof Logical l) {
            var result = condition(l.operands().getFirst());
            for (var child : l.operands().subList(1, l.operands().size())) result = CodeBlock.of("($L).$N($L)", result, l.operator(), condition(child));
            return result;
        }
        var p = (Predicate)c; var s = p.source();
        if (p.kind().equals("status")) return CodeBlock.of("$T.of(__response.status() $L $L)", CONDITION,
                s.operator().equals("=") ? "==" : s.operator(), Integer.parseInt(s.literal()));
        String path = "java.util.Optional.of(__dto)"; int index = 0;
        for (var f : p.fields()) {
            String name = "__field" + index++;
            path += (f.optional() ? ".flatMap(" : ".map(") + name + " -> " + name + "." + f.name() + "())";
        }
        CodeBlock test;
        if (p.kind().equals("presence")) test = CodeBlock.of("$T.of($L.$N())", CONDITION, path,
                s.operator().equals("absent") ? "isEmpty" : "isPresent");
        else if (p.kind().equals("number")) test = CodeBlock.of("$T.number($L, $S, $S)", VALUES, path, s.literal(), s.operator());
        else test = CodeBlock.of("$T.equal($L, $L, $L)", VALUES, path,
                p.kind().equals("string") ? CodeBlock.of("$S", s.literal()) : CodeBlock.of("$L", s.literal()), s.operator().equals("!="));
        return CodeBlock.of("__error.evaluate(__dto -> $L)", test);
    }
}
