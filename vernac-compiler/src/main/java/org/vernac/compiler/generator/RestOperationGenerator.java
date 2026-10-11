// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import java.util.Optional;
import java.util.stream.Collectors;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.rest.ResolvedRestOperation;

/** Lowers a resolved HTTP plan into the same behavior companion used by Java adapters. */
final class RestOperationGenerator {
    CodeBlock body(OutboundAdapterNode adapter, CompilationUnitNode unit, ResolvedRestOperation plan) {
        var rest = adapter.rest().orElseThrow();
        var operation = plan.source();
        var method = operation.method();
        boolean isVoid = method.returnType().name().equals("void");
        boolean optional = method.returnType().isOptional();
        boolean absent = operation.absentStatus().isPresent();
        ClassName mappings = ClassName.get(unit.namespace() + ".adapter.outbound.mapping", adapter.name() + "Mappings");
        ClassName requests = ClassName.get("org.vernac.runtime.http", "HttpRequests");
        ClassName json = ClassName.get("org.vernac.runtime.http", "HttpJson");
        ClassName failures = ClassName.get("org.vernac.runtime.http", "HttpFailures");
        var code = CodeBlock.builder();
        if (!isVoid) code.add("return ");
        if (optional && !absent) code.add("$T.of(", Optional.class);
        code.add("self.$N().$N(self.invocation(), () -> {\n", rest.client(), isVoid ? "execute" : absent ? "optionalOn404" : "required").indent();
        code.addStatement("var __request = $T.at(self.$N(), $S)", requests, rest.base(), operation.route());
        for (var binding : plan.bindings()) {
            String expression = expression(binding.input());
            if (!binding.source().kind().equals("path") && !binding.input().optional()) expression = "java.util.Optional.of(" + expression + ")";
            code.addStatement("__request.$N($S, $L)", binding.source().kind(), binding.source().target(), expression);
        }
        if (plan.response() != null && operation.bindings().stream().noneMatch(b -> b.kind().equals("header") && b.target().equalsIgnoreCase("Accept")))
            code.addStatement("__request.header($S, $T.of($S))", "Accept", Optional.class, "application/json");
        if (plan.body() == null) code.addStatement("return __request.build($S)", operation.verb());
        else code.addStatement("return __request.build($S, $T.write($T.$N($L)))", operation.verb(), json, mappings,
                plan.body().mapping().source().name(), expression(plan.body().input()));
        code.unindent().add("},\n");
        if (!isVoid) code.add("__response -> $T.$N($T.read(__response, $T.class)),\n", mappings,
                plan.response().mapping().source().name(), json, plan.response().dto().javaType());
        if (operation.errorImplementation().isPresent()) {
            String arguments = method.parameters().stream().map(FieldNode::name).collect(Collectors.joining(", "));
            code.add("(__invocation, __response) -> $L.$N(self, $L__response)", operation.errorImplementation().get(),
                    method.name(), arguments.isEmpty() ? "" : arguments + ", ");
        } else if (plan.errors() != null) code.add(new RestErrorGenerator().handler(plan.errors()));
        else code.add("$T::defaultFailure", failures);
        code.add(optional && !absent ? "));\n" : ");\n");
        return code.build();
    }
    private String expression(ResolvedRestOperation.Input input) {
        String result = input.parameter().name();
        boolean optional = input.parameter().type().isOptional();
        int index = 0;
        for (var field : input.fields()) {
            if (optional) {
                String local = "__value" + index++;
                result += (field.optional() ? ".flatMap(" : ".map(") + local + " -> " + local + "." + field.name() + "())";
            } else result += "." + field.name() + "()";
            optional |= field.optional();
        }
        return result;
    }
}
