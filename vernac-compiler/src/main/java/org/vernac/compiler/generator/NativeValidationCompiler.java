// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import javax.lang.model.element.Modifier;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.runtime.NativeValidation;

import java.util.List;
import java.util.Set;

/** Resolves state reads and emits an executable predicate against the domain read interface. */
public final class NativeValidationCompiler {
    private static final ResolvedType.Builtin BOOL = new ResolvedType.Builtin(boolean.class);
    private static final ResolvedType.Builtin INT = new ResolvedType.Builtin(int.class);

    private final TopLevelDefinition owner;
    private final ResolvedProject project;
    private final List<FieldNode> parameters;
    private final List<Binding> bindings;
    private final boolean invocation;

    NativeValidationCompiler(TopLevelDefinition owner, ResolvedProject project) {
        this(owner, project, List.of(), List.of(), false);
    }

    private NativeValidationCompiler(TopLevelDefinition owner, ResolvedProject project,
                                     List<FieldNode> parameters, List<Binding> bindings, boolean invocation) {
        this.owner = owner;
        this.project = project;
        this.parameters = parameters;
        this.bindings = bindings;
        this.invocation = invocation;
    }

    public static List<CompilerDiagnostic> diagnostics(ResolvedProject project) {
        var diagnostics = new java.util.ArrayList<CompilerDiagnostic>();
        for (var source : project.project().sources()) {
            if (source.external()) continue;
            for (var definition : source.unit().definitions()) {
                var rules = switch (definition) {
                    case ValueObjectNode value -> value.validations();
                    case EntityNode entity -> entity.validations();
                    case AggregateNode aggregate -> aggregate.validations();
                    case EventNode event -> event.validations();
                    default -> List.<ValidationRuleNode>of();
                };
                var methods = methods(definition);
                for (var method : methods) {
                    if (method.nativeBody().isEmpty()) continue;
                    try {
                        checkNativeMethod(definition, method);
                        String name = definition instanceof EntityNode entity ? entity.name()
                                : ((AggregateNode) definition).name();
                        var owner = ClassName.get(source.unit().namespace() + ".domain", name);
                        emitBody(MethodSpec.methodBuilder(method.name()), TypeSpec.classBuilder("Check"),
                                owner, definition, method, 0, project);
                    } catch (SemanticValidationException failure) {
                        diagnostics.addAll(failure.diagnostics());
                    }
                }
                var compiler = new NativeValidationCompiler(definition, project);
                for (var rule : rules) {
                    if (rule.nativeRule().isEmpty()) continue;
                    try {
                        compiler.compile(rule.nativeRule().orElseThrow().predicate());
                    } catch (SemanticValidationException failure) {
                        diagnostics.addAll(failure.diagnostics());
                    }
                }
            }
        }
        return diagnostics;
    }

    static void emitBody(MethodSpec.Builder implementation, TypeSpec.Builder companion, ClassName ownerType,
                         TopLevelDefinition owner, MethodNode method, int index, ResolvedProject project) {
        checkNativeMethod(owner, method);
        var compiler = new NativeValidationCompiler(owner, project, method.parameters(), List.of(), true);
        var receiver = BehaviorGenerator.receiver(ownerType, method);
        var invocation = ParameterizedTypeName.get(ClassName.get(NativeValidation.Invocation.class), receiver);
        var expressionType = ParameterizedTypeName.get(ClassName.get(NativeValidation.Expression.class), invocation);
        var arguments = method.parameters().stream().map(FieldNode::name).collect(java.util.stream.Collectors.joining(", "));
        implementation.addStatement("var __vernacInvocation = new $T(self, $T.of($L))",
                invocation, List.class, arguments);
        int step = 0;
        boolean returned = false;
        var returns = project.typeOf(method.returnType());
        for (var statement : method.nativeBody()) {
            var at = statementLocation(statement);
            if (returned) throw error(at, "Native statement after return");
            String name = "nativeBehavior" + index + "Step" + step++;
            switch (statement) {
                case NativeStatement.Require rule -> {
                    addProgram(companion, expressionType, name, compiler.compile(rule.predicate()));
                    implementation.beginControlFlow("if (!(boolean) $N.evaluate(__vernacInvocation))", name)
                            .addStatement("throw new $T($S)", NativeValidation.Rejection.class, rule.rejection())
                            .endControlFlow();
                }
                case NativeStatement.Assign assignment -> {
                    if (method.mode() != MethodNode.Mode.MODIFY)
                        throw error(at, "Only modify behaviors may assign state");
                    var field = fields(owner).stream().filter(f -> f.name().equals(assignment.field()) && f.isMutable())
                            .findFirst().orElseThrow(() -> error(at, "Unknown mutable field '" + assignment.field() + "'"));
                    var type = project.typeOf(field.type());
                    if (field.type().isOptional()) throw error(at, "Native assignments require a non-optional field");
                    addProgram(companion, expressionType, name, compiler.compileValue(assignment.value(), type));
                    implementation.addStatement("self.$N(($T) $N.evaluate(__vernacInvocation))",
                            field.name(), ResolvedJavaTypes.javaType(type), name);
                }
                case NativeStatement.Send send -> {
                    if (method.mode() != MethodNode.Mode.MODIFY)
                        throw error(at, "Only modify behaviors may send modification messages");
                    if (!(send.expression() instanceof KeywordExpression.Message message))
                        throw error(at, "Expected a keyword message");
                    addProgram(companion, expressionType, name, compiler.call(message, MethodNode.Mode.MODIFY).program());
                    implementation.addStatement("$N.evaluate(__vernacInvocation)", name);
                }
                case NativeStatement.Return result -> {
                    if (returns instanceof ResolvedType.VoidReturn)
                        throw error(at, "Void native behavior cannot return a value");
                    addProgram(companion, expressionType, name, compiler.compileValue(result.value(), returns));
                    implementation.addStatement("return ($T) $N.evaluate(__vernacInvocation)",
                            ResolvedJavaTypes.javaType(returns), name);
                    returned = true;
                }
            }
        }
        if (!returned && !(returns instanceof ResolvedType.VoidReturn))
            throw error(method.location(), "Native behavior must return a value");
    }

    private static SourceLocation statementLocation(NativeStatement statement) {
        return switch (statement) {
            case NativeStatement.Require rule -> rule.predicate().location();
            case NativeStatement.Assign assignment -> assignment.location();
            case NativeStatement.Send send -> send.expression().location();
            case NativeStatement.Return result -> result.value().location();
        };
    }

    private static void checkNativeMethod(TopLevelDefinition owner, MethodNode method) {
        if (!(owner instanceof EntityNode || owner instanceof AggregateNode) || method.mode() == MethodNode.Mode.DEFAULT)
            throw error(method.location(), "Native behavior currently requires an entity or aggregate read or modify method");
        if (method.returnType().isOptional() || method.parameters().stream().anyMatch(p -> p.type().isOptional()))
            throw error(method.location(), "Native behavior currently requires non-optional parameters and result");
        if (method.parameters().stream().anyMatch(p -> p.name().equals("__vernacInvocation")))
            throw error(method.location(), "Reserved native behavior parameter '__vernacInvocation'");
    }

    private static void addProgram(TypeSpec.Builder companion, TypeName type, String name, CodeBlock code) {
        companion.addField(FieldSpec.builder(type, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(code).build());
    }

    private CodeBlock compileValue(KeywordExpression value, ResolvedType expected) {
        var result = resolve(value);
        if (result.optional() || !result.type().equals(expected))
            throw error(value, "Expression does not match the declared non-optional type");
        return result.program();
    }

    CodeBlock compile(KeywordExpression predicate) {
        var result = resolve(predicate);
        require(predicate, result, BOOL);
        return result.program();
    }

    private record Typed(CodeBlock program, ResolvedType type, boolean optional) { }
    private record Read(CodeBlock code, FieldNode field) { }

    private Typed resolve(KeywordExpression expression) {
        return switch (expression) {
            case KeywordExpression.Literal literal -> literal(literal);
            case KeywordExpression.Name name -> {
                var binding = binding(name);
                yield new Typed(CodeBlock.of("$T.access(context -> $L)", NativeValidation.class, binding.code()),
                        binding.type(), false);
            }
            case KeywordExpression.Match match -> match(match);
            case KeywordExpression.Message message -> call(message, MethodNode.Mode.READ);
            case KeywordExpression.UnaryMessage message -> {
                var read = read(message);
                yield new Typed(CodeBlock.of("$T.access(context -> $L)", NativeValidation.class, read.code()),
                        project.typeOf(read.field().type()), read.field().type().isOptional());
            }
            case KeywordExpression.Prefix prefix -> {
                var result = resolve(prefix.operand());
                var expected = prefix.operator().equals("!") ? BOOL : INT;
                require(prefix.operand(), result, expected);
                yield new Typed(CodeBlock.of("$T.prefix($S, $L)", NativeValidation.class,
                        prefix.operator(), result.program()), expected, false);
            }
            case KeywordExpression.Binary binary -> binary(binary);
        };
    }

    private record Binding(String name, ResolvedType type) { }

    private record Local(CodeBlock code, ResolvedType type) { }

    private Local binding(KeywordExpression.Name name) {
        for (int index = bindings.size() - 1; index >= 0; index--) {
            var binding = bindings.get(index);
            if (binding.name().equals(name.name())) {
                var scope = context(bindings.size() - 1 - index);
                return new Local(CodeBlock.of("$L.value()", scope), binding.type());
            }
        }
        for (int index = 0; index < parameters.size(); index++) {
            var parameter = parameters.get(index);
            if (parameter.name().equals(name.name())) {
                return new Local(CodeBlock.of("$L.arguments().get($L)", context(bindings.size()), index),
                        project.typeOf(parameter.type()));
            }
        }
        throw error(name, "Unknown native binding '" + name.name() + "'");
    }

    private static String context(int depth) {
        return "context" + ".outer()".repeat(depth);
    }

    private Typed match(KeywordExpression.Match expression) {
        var optional = resolve(expression.subject());
        if (!optional.optional()) throw error(expression.subject(), "Match requires an optional value");
        String name = expression.binding().name();
        if (name.equals("self") || !org.vernac.language.VernacNames.isIdentifier(name)
                || parameters.stream().anyMatch(parameter -> parameter.name().equals(name))
                || bindings.stream().anyMatch(binding -> binding.name().equals(name))) {
            throw error(expression.binding(), "Invalid or duplicate match binding '" + name + "'");
        }
        var nested = new java.util.ArrayList<>(bindings);
        nested.add(new Binding(name, optional.type()));
        var present = new NativeValidationCompiler(owner, project, parameters, nested, invocation)
                .resolve(expression.onPresent());
        var absent = resolve(expression.onAbsent());
        if (!present.type().equals(absent.type()) || present.optional() != absent.optional())
            throw error(expression.onAbsent(), "Match alternatives must have the same type and optionality");
        return new Typed(CodeBlock.of("$T.match($L, $L, $L)", NativeValidation.class,
                optional.program(), present.program(), absent.program()), present.type(), present.optional());
    }

    private Typed call(KeywordExpression.Message message, MethodNode.Mode effect) {
        if (!(message.receiver() instanceof KeywordExpression.Name name) || !name.name().equals("self"))
            throw error(message, "Native keyword sends currently require self as receiver");
        String selector = message.parts().getFirst().name();
        var candidates = methods(owner).stream()
                .filter(method -> method.mode() == effect && method.accessModifier().equals("public"))
                .filter(method -> method.name().equals(selector) && method.parameters().size() == message.parts().size())
                .filter(method -> java.util.stream.IntStream.range(1, message.parts().size()).allMatch(
                        i -> method.parameters().get(i).name().equals(message.parts().get(i).name())))
                .toList();
        if (candidates.size() != 1)
            throw error(message, "Expected one " + (effect == MethodNode.Mode.READ ? "readable" : "modifying")
                    + " behavior for selector '" + message.selector() + "'");
        var method = candidates.getFirst();
        var result = project.typeOf(method.returnType());
        boolean nothing = result instanceof ResolvedType.VoidReturn;
        if (nothing && effect == MethodNode.Mode.READ)
            throw error(message, "Native keyword read must return a value");
        var programs = new java.util.ArrayList<CodeBlock>();
        var arguments = new java.util.ArrayList<CodeBlock>();
        for (int i = 0; i < message.parts().size(); i++) {
            var parameter = method.parameters().get(i);
            var expected = project.typeOf(parameter.type());
            programs.add(compileValue(message.parts().get(i).argument(), expected));
            arguments.add(CodeBlock.of("($T) values.get($L)", ResolvedJavaTypes.javaType(expected), i));
        }
        var invocation = CodeBlock.of("$L.$N($L)", self(), method.name(), CodeBlock.join(arguments, ", "));
        var lambdaBody = nothing ? CodeBlock.of("{ $L; return true; }", invocation) : invocation;
        return new Typed(CodeBlock.of("$T.call($T.of($L), (context, values) -> $L)",
                NativeValidation.class, List.class, CodeBlock.join(programs, ", "), lambdaBody),
                result, method.returnType().isOptional());
    }

    private CodeBlock self() {
        return CodeBlock.of("$L$L", context(bindings.size()), invocation ? ".self()" : "");
    }

    private static List<MethodNode> methods(TopLevelDefinition definition) {
        return switch (definition) {
            case ValueObjectNode value -> value.methods();
            case EntityNode entity -> entity.methods();
            case AggregateNode aggregate -> aggregate.methods();
            default -> List.of();
        };
    }

    private Typed binary(KeywordExpression.Binary expression) {
        var left = resolve(expression.left());
        var right = resolve(expression.right());
        String op = expression.operator();
        ResolvedType resultType;
        switch (op) {
            case "==", "!=" -> {
                if (!left.type().equals(right.type()) || left.optional() != right.optional())
                    throw error(expression.right(), "Equality requires operands of the same type and optionality");
                resultType = BOOL;
            }
            case "&&", "||" -> {
                require(expression.left(), left, BOOL);
                require(expression.right(), right, BOOL);
                resultType = BOOL;
            }
            default -> {
                require(expression.left(), left, INT);
                require(expression.right(), right, INT);
                resultType = Set.of("<", ">", "<=", ">=").contains(op) ? BOOL : INT;
            }
        }
        return new Typed(CodeBlock.of("$T.binary($S, $L, $L)", NativeValidation.class,
                op, left.program(), right.program()), resultType, false);
    }

    private Read read(KeywordExpression.UnaryMessage expression) {
        CodeBlock target;
        TopLevelDefinition definition;
        if (expression.receiver() instanceof KeywordExpression.Name name && name.name().equals("self")) {
            target = self();
            definition = owner;
        } else if (expression.receiver() instanceof KeywordExpression.Name name) {
            var local = binding(name);
            definition = readValueType(expression, local.type());
            target = CodeBlock.of("(($T) $L)", ResolvedJavaTypes.javaType(local.type()), local.code());
        } else if (expression.receiver() instanceof KeywordExpression.UnaryMessage parent) {
            var read = read(parent);
            if (read.field().type().isOptional()) throw error(expression, "Match an optional value before reading its fields");
            definition = readValueType(expression, project.typeOf(read.field().type()));
            target = read.code();
        } else {
            throw error(expression, "Expected self or a readable value");
        }
        var field = fields(definition).stream().filter(f -> f.name().equals(expression.selector()))
                .findFirst().orElseThrow(() -> error(expression, "Unknown readable field '" + expression.selector() + "'"));
        return new Read(CodeBlock.of("$L.$N()", target, field.name()), field);
    }

    private TopLevelDefinition readValueType(KeywordExpression expression, ResolvedType type) {
        // Traversing a nested entity would expose an opaque object rather than its validation view.
        if (type instanceof ResolvedType.Declared declared) {
            return project.project().sources().stream()
                    .filter(source -> source.unit().namespace().equals(declared.symbol().identity().namespace()))
                    .flatMap(source -> source.unit().valueObjects().stream())
                    .filter(value -> value.name().equals(declared.symbol().identity().name()))
                    .findFirst().orElseThrow(() -> error(expression, "Expected a value object for a nested field read"));
        }
        throw error(expression, "Expected a value object for a nested field read");
    }

    private static List<FieldNode> fields(TopLevelDefinition owner) {
        return switch (owner) {
            case ValueObjectNode value -> value.fields();
            case EntityNode entity -> entity.fields();
            case AggregateNode aggregate -> aggregate.fields();
            case EventNode event -> event.fields();
            default -> throw new IllegalArgumentException("Unsupported native validation owner " + owner);
        };
    }

    private Typed literal(KeywordExpression.Literal literal) {
        var spelling = literal.spelling();
        try {
            return switch (literal.kind()) {
                case BOOLEAN -> new Typed(CodeBlock.of("$T.literal($L)", NativeValidation.class, spelling), BOOL, false);
                case INTEGER -> new Typed(CodeBlock.of("$T.literal($L)", NativeValidation.class,
                        Integer.parseInt(spelling)), INT, false);
                case STRING -> new Typed(CodeBlock.of("$T.literal($S)", NativeValidation.class,
                        spelling.substring(1, spelling.length() - 1).translateEscapes()),
                        new ResolvedType.Builtin(String.class), false);
                case DECIMAL -> throw error(literal, "Decimal literals are not supported in native validation yet");
            };
        } catch (IllegalArgumentException failure) {
            throw error(literal, "Invalid literal '" + spelling + "'");
        }
    }

    private static void require(KeywordExpression at, Typed actual, ResolvedType.Builtin expected) {
        if (actual.optional() || !actual.type().equals(expected))
            throw error(at, "Expected a non-optional " + expected.javaType().getSimpleName());
    }

    private static SemanticValidationException error(KeywordExpression at, String message) {
        return error(at.location(), message);
    }

    private static SemanticValidationException error(SourceLocation at, String message) {
        return new SemanticValidationException(List.of(CompilerDiagnostic.error(at, message)));
    }
}
