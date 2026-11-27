// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.vernac.compiler.parser.VernacParser.*;
import org.vernac.language.VernacNames;

import java.util.List;

/** Finds lexical bindings in the same recovered syntax tree used for editor navigation. */
final class NativeBindingNavigation {
    private NativeBindingNavigation() { }

    static ParserRuleContext declarationAt(CompilationUnitContext tree, int point) {
        var reference = referenceAt(tree, point);
        if (reference == null) return null;
        String name = reference.getText();
        ParseTree child = reference;
        for (var scope = reference.getParent(); scope != null; scope = scope.getParent()) {
            var enclosed = child;
            ParserRuleContext declaration = switch (scope) {
                case KeywordPrimaryContext primary -> child == primary.blockBody ? named(primary.blockBinding, name)
                        : child == primary.onPresent ? named(primary.binding, name) : null;
                case NativeStatementBlockContext block -> precedingLet(block.nativeBehaviorStatement(), child, name);
                case NativeBehaviorBodyContext body -> precedingLet(body.nativeBehaviorStatement(), child, name);
                case BehaviorMethodContext method -> parameters(method.parameterList(), name);
                case UsecaseDefinitionContext useCase when enclosed instanceof UsecaseBehaviorMemberContext member
                        && member.nativeBehaviorBody() != null -> {
                    var input = parameters(useCase.inputs, name);
                    yield input == null ? parameters(useCase.dependencies, name) : input;
                }
                case ListenerDefinitionContext listener when enclosed instanceof UsecaseBehaviorMemberContext member
                        && member.nativeBehaviorBody() != null -> name.equals("event") ? listener.eventType
                        : parameters(listener.dependencies, name);
                default -> null;
            };
            if (declaration != null) return declaration;
            child = scope;
        }
        return null;
    }

    private static VariableNameContext referenceAt(ParserRuleContext context, int point) {
        var start = context.getStart();
        var stop = context.getStop();
        if (start == null || stop == null || point < start.getStartIndex() || point > stop.getStopIndex()) return null;
        if (context instanceof KeywordPrimaryContext primary) {
            var name = primary.variableName();
            if (name != null && name != primary.binding && name != primary.blockBinding && contains(name, point)) return name;
        }
        for (int index = 0; index < context.getChildCount(); index++) {
            if (context.getChild(index) instanceof ParserRuleContext child) {
                var reference = referenceAt(child, point);
                if (reference != null) return reference;
            }
        }
        return null;
    }

    private static boolean contains(ParserRuleContext context, int point) {
        return context.getStart() != null && context.getStop() != null
                && point >= context.getStart().getStartIndex() && point <= context.getStop().getStopIndex();
    }

    private static ParserRuleContext precedingLet(List<NativeBehaviorStatementContext> statements, ParseTree child,
                                                  String name) {
        ParserRuleContext result = null;
        for (var statement : statements) {
            if (statement == child) break;
            if (named(statement.local, name) != null) result = statement.local;
        }
        return result;
    }

    private static ParserRuleContext parameters(ParameterListContext list, String name) {
        return list == null ? null : parameters(list.parameter(), name);
    }

    private static ParserRuleContext parameters(List<ParameterContext> parameters, String name) {
        for (var parameter : parameters) {
            if (named(parameter.name, name) != null) return parameter.name;
            if (parameter.name == null && parameter.paramType != null && parameter.paramType.rawType != null) {
                var type = parameter.paramType.rawType;
                String simple = type.getStop() == null ? "" : type.getStop().getText();
                if (!simple.isEmpty() && VernacNames.isStart(simple.codePointAt(0))
                        && VernacNames.defaultMemberName(type.getText()).equals(name)) {
                    return type;
                }
            }
        }
        return null;
    }

    private static ParserRuleContext named(ParserRuleContext declaration, String name) {
        return declaration != null && declaration.getStart().getStartIndex() >= 0 && declaration.getText().equals(name)
                ? declaration : null;
    }
}
