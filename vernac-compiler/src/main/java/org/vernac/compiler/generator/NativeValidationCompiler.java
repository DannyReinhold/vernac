// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import javax.lang.model.element.Modifier;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.pipeline.NativeKeywordMessages;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.compiler.symbols.TypeSymbol;
import org.vernac.runtime.NativeBehavior;
import org.vernac.runtime.NativeValidation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Resolves state reads and emits an executable predicate against the domain read interface. */
public final class NativeValidationCompiler {
    private static final int BEHAVIOR_FORMAT_VERSION = 1;
    private static final ResolvedType.Builtin BOOL = new ResolvedType.Builtin(boolean.class);
    private static final ResolvedType.Builtin INT = new ResolvedType.Builtin(int.class);
    private static final ResolvedType.Builtin DECIMAL = new ResolvedType.Builtin(BigDecimal.class);

    private final TopLevelDefinition owner;
    private final ResolvedProject project;
    private final List<FieldNode> parameters;
    private final List<Binding> bindings;
    private final List<LocalSlot> locals;
    private final boolean invocation;

    NativeValidationCompiler(TopLevelDefinition owner, ResolvedProject project) {
        this(owner, project, List.of(), List.of(), List.of(), false);
    }

    private NativeValidationCompiler(TopLevelDefinition owner, ResolvedProject project,
                                     List<FieldNode> parameters, List<Binding> bindings, List<LocalSlot> locals,
                                     boolean invocation) {
        this.owner = owner;
        this.project = project;
        this.parameters = parameters;
        this.bindings = bindings;
        this.locals = locals;
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
                if (definition instanceof UseCaseNode useCase && !useCase.nativeBody().isEmpty()) {
                    try {
                        var access = ClassName.get(source.unit().namespace() + ".usecase.access", useCase.name() + "Access");
                        emitUseCase(MethodSpec.methodBuilder("execute"), TypeSpec.classBuilder("Check"), access, useCase, project);
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
        emit(implementation, companion, BehaviorGenerator.receiver(ownerType, method), owner,
                method.parameters(), method.nativeBody(), method.mode() == MethodNode.Mode.MODIFY,
                project.typeOf(method.returnType()), null, method.location(), index, project,
                BehaviorGenerator.companion(ownerType));
    }

    static void emitUseCase(MethodSpec.Builder implementation, TypeSpec.Builder companion, ClassName receiver,
                            UseCaseNode useCase, ResolvedProject project) {
        if (useCase.resultType().map(TypeNode::isOptional).orElse(false)
                || useCase.parameters().stream().anyMatch(parameter -> parameter.type().isOptional())) {
            throw error(useCase.location(), "Native execute currently requires non-optional inputs and result");
        }
        var result = useCase.resultType().map(project::typeOf).orElseGet(ResolvedType.VoidReturn::new);
        var owner = ClassName.get(receiver.packageName().substring(0, receiver.packageName().lastIndexOf('.')), useCase.name());
        var compoundResult = useCase.resultFields().isEmpty() ? null : owner.nestedClass("Result");
        emit(implementation, companion, receiver, useCase, useCase.parameters(), useCase.nativeBody(),
                true, result, compoundResult, useCase.location(), 0, project, BehaviorGenerator.companion(owner));
    }

    private static void emit(MethodSpec.Builder implementation, TypeSpec.Builder companion, ClassName receiver,
                             TopLevelDefinition owner, List<FieldNode> parameters, List<NativeStatement> body,
                             boolean canModify, ResolvedType returns, ClassName compoundResult, SourceLocation location, int index,
                             ResolvedProject project, ClassName implementationClass) {
        if (parameters.stream().anyMatch(p -> p.name().equals("__vernacInvocation")))
            throw error(location, "Reserved native parameter '__vernacInvocation'");
        var locals = new java.util.ArrayList<LocalSlot>();
        var compiler = new NativeValidationCompiler(owner, project, parameters, List.of(), locals, true);
        var bodyProgram = compiler.compileBlock(body, canModify, returns, compoundResult);
        boolean returnsValue = compoundResult != null || !(returns instanceof ResolvedType.VoidReturn);
        if (!bodyProgram.returns() && returnsValue) throw error(location, "Native behavior must return a value");

        String name = "nativeBehavior" + index;
        var programType = ParameterizedTypeName.get(ClassName.get(NativeBehavior.Program.class), receiver);
        companion.addField(FieldSpec.builder(programType, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T<>($L, $L, $T.of($L), $T.class, $L)", NativeBehavior.Program.class,
                        BEHAVIOR_FORMAT_VERSION, bodyProgram.localCount(), List.class,
                        CodeBlock.join(bodyProgram.instructions(), ",\n"), implementationClass, index)
                .build());
        var arguments = parameters.stream().map(FieldNode::name).collect(java.util.stream.Collectors.joining(", "));
        var execute = CodeBlock.of("$N.execute(self, $T.of($L), $T.class, $L)", name, List.class, arguments,
                implementationClass, index);
        if (returnsValue) {
            TypeName resultType = compoundResult != null ? compoundResult : ResolvedJavaTypes.javaType(returns);
            implementation.addStatement("return ($T) $L", resultType, execute);
        } else {
            implementation.addStatement("$L", execute);
        }
    }

    private record CompiledBlock(List<CodeBlock> instructions, boolean returns, int localCount) { }

    private CompiledBlock compileBranch(List<NativeStatement> steps, boolean canModify, ResolvedType returns,
                                        ClassName compoundResult) {
        return new NativeValidationCompiler(owner, project, parameters, bindings, new java.util.ArrayList<>(locals), invocation)
                .compileBlock(steps, canModify, returns, compoundResult);
    }

    private CompiledBlock compileBlock(List<NativeStatement> body, boolean canModify, ResolvedType returns,
                                       ClassName compoundResult) {
        var instructions = new java.util.ArrayList<CodeBlock>();
        boolean returned = false;
        int localCount = locals.size();
        for (var statement : body) {
            var at = statement.location();
            if (returned) throw error(at, "Native statement after return");
            instructions.add(switch (statement) {
                case NativeStatement.Let let -> {
                    checkBindingName(let.name());
                    var value = resolve(let.value());
                    if (value.type() instanceof ResolvedType.VoidReturn)
                        throw error(let.value(), "A local binding requires a value");
                    int slot = locals.size();
                    locals.add(new LocalSlot(let.name().name(), value.type(), value.optional()));
                    yield CodeBlock.of("new $T<>($L, $L)", NativeBehavior.Bind.class, slot, value.program());
                }
                case NativeStatement.Require rule -> CodeBlock.of("new $T<>($L, $S)", NativeBehavior.Require.class,
                        compile(rule.predicate()), rule.rejection());
                case NativeStatement.Assign assignment -> {
                    if (!canModify || owner instanceof UseCaseNode)
                        throw error(at, "Only modify behaviors may assign state");
                    var field = fields(owner).stream().filter(f -> f.name().equals(assignment.field()) && f.isMutable())
                            .findFirst().orElseThrow(() -> error(at, "Unknown mutable field '" + assignment.field() + "'"));
                    var type = project.typeOf(field.type());
                    if (field.type().isOptional()) throw error(at, "Native assignments require a non-optional field");
                    yield CodeBlock.of("new $T<>($L, (target, value) -> target.$N(($T) value))",
                            NativeBehavior.Assign.class, compileValue(assignment.value(), type),
                            field.name(), ResolvedJavaTypes.javaType(type));
                }
                case NativeStatement.Send send -> {
                    if (!canModify)
                        throw error(at, "Only modify behaviors may send modification messages");
                    if (!(send.expression() instanceof KeywordExpression.Message message))
                        throw error(at, "Expected a keyword message");
                    yield CodeBlock.of("new $T<>($L)", NativeBehavior.Send.class,
                            call(message, MethodNode.Mode.MODIFY).program());
                }
                case NativeStatement.Branch branch -> {
                    var condition = compile(branch.condition());
                    var onTrue = compileBranch(branch.onTrue(), canModify, returns, compoundResult);
                    var onFalse = compileBranch(branch.onFalse(), canModify, returns, compoundResult);
                    localCount = Math.max(localCount, Math.max(onTrue.localCount(), onFalse.localCount()));
                    returned = onTrue.returns() && onFalse.returns();
                    yield CodeBlock.of("new $T<>($L, $T.of($L), $T.of($L))", NativeBehavior.Branch.class, condition,
                            List.class, CodeBlock.join(onTrue.instructions(), ",\n"),
                            List.class, CodeBlock.join(onFalse.instructions(), ",\n"));
                }
                case NativeStatement.Return result -> {
                    returned = true;
                    CodeBlock value;
                    if (compoundResult != null) {
                        value = compileResult(result.value(), (UseCaseNode) owner, compoundResult);
                    } else {
                        if (returns instanceof ResolvedType.VoidReturn)
                            throw error(at, "Void native behavior cannot return a value");
                        value = compileValue(result.value(), returns);
                    }
                    yield CodeBlock.of("new $T<>($L)", NativeBehavior.Return.class, value);
                }
            });
        }
        return new CompiledBlock(instructions, returned, Math.max(localCount, locals.size()));
    }

    private static void checkNativeMethod(TopLevelDefinition owner, MethodNode method) {
        if (!(owner instanceof EntityNode || owner instanceof AggregateNode) || method.mode() == MethodNode.Mode.DEFAULT)
            throw error(method.location(), "Native behavior currently requires an entity or aggregate read or modify method");
        if (method.returnType().isOptional() || method.parameters().stream().anyMatch(p -> p.type().isOptional()))
            throw error(method.location(), "Native behavior currently requires non-optional parameters and result");
    }

    private CodeBlock compileResult(KeywordExpression result, UseCaseNode useCase, ClassName resultType) {
        if (!(result instanceof KeywordExpression.Message message)
                || !(message.receiver() instanceof KeywordExpression.Name name) || !name.name().equals("Result"))
            throw error(result, "A compound result must be returned using a Result keyword constructor");
        if (!useCase.resultFields().stream().map(FieldNode::name).toList()
                .equals(message.parts().stream().map(KeywordExpression.KeywordPart::name).toList()))
            throw error(result, "Result selector must name each declared field in order");
        var arguments = arguments(message, useCase.resultFields());
        return CodeBlock.of("$T.call($T.of($L), (context, values) -> $T.of($L))",
                NativeValidation.class, List.class, CodeBlock.join(arguments.programs(), ", "),
                resultType, arguments.values());
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
                        binding.type(), binding.optional());
            }
            case KeywordExpression.Match match -> match(match);
            case KeywordExpression.Block block -> throw error(block, "A block requires a collection message");
            case KeywordExpression.Message message -> readMessage(message);
            case KeywordExpression.UnaryMessage message -> {
                var constant = enumConstant(message);
                if (constant != null) yield constant;
                if (message.selector().equals(NativeKeywordMessages.AS_DECIMAL)) {
                    var operand = scalarReceiver(message.receiver());
                    if (operand != null && operand.type().equals(INT)) {
                        require(message.receiver(), operand, INT);
                        yield new Typed(CodeBlock.of("new $T<>($L)", NativeValidation.ToDecimal.class, operand.program()),
                                DECIMAL, false);
                    }
                }
                var read = read(message);
                yield new Typed(CodeBlock.of("$T.access(context -> $L)", NativeValidation.class, read.code()),
                        project.typeOf(read.field().type()), read.field().type().isOptional());
            }
            case KeywordExpression.Prefix prefix -> {
                var result = resolve(prefix.operand());
                var expected = prefix.operator().equals("!") ? BOOL : numericType(result.type());
                require(prefix.operand(), result, expected);
                yield new Typed(CodeBlock.of("$T.prefix($S, $L)", NativeValidation.class,
                        prefix.operator(), result.program()), expected, false);
            }
            case KeywordExpression.Binary binary -> binary(binary);
        };
    }

    private Typed enumConstant(KeywordExpression.UnaryMessage message) {
        if (!(message.receiver() instanceof KeywordExpression.Name receiver)
                || receiver.name().equals("self") || hasBinding(receiver.name())) return null;
        var candidate = scopeFor(owner).resolve(receiver.name(), receiver.location()).type().orElse(null);
        if (!(candidate instanceof ResolvedType.Declared type) || type.symbol().kind() != TypeSymbol.Kind.ENUM) return null;
        var enumeration = (ValueObjectNode) readValueType(message, type);
        if (enumeration.enumConstants().stream().noneMatch(member -> member.name().equals(message.selector()))) {
            throw error(message, "Unknown enum member '" + message.selector() + "'");
        }
        return new Typed(CodeBlock.of("$T.literal($T.$L)", NativeValidation.class,
                ResolvedJavaTypes.javaType(type), message.selector()), type, false);
    }

    private Typed scalarReceiver(KeywordExpression receiver) {
        if (receiver instanceof KeywordExpression.Name name && !hasBinding(name.name())) return null;
        return resolve(receiver);
    }

    private Typed readMessage(KeywordExpression.Message message) {
        String selector = message.selector();
        if (message.receiver() instanceof KeywordExpression.Name name && !hasBinding(name.name())) {
            var factory = NativeKeywordMessages.temporalFactory(name.name(), selector);
            if (factory != null) return temporalMessage(message, factory, null);
        }
        boolean conditional = selector.equals(NativeKeywordMessages.CONDITIONAL);
        boolean round = selector.equals(NativeKeywordMessages.ROUNDED);
        boolean divide = selector.equals(NativeKeywordMessages.DIVIDED);
        if (conditional || round || divide || NativeKeywordMessages.isTemporalSelector(selector)
                || NativeKeywordMessages.isCollectionSelector(selector)) {
            var receiver = scalarReceiver(message.receiver());
            if (receiver != null && NativeKeywordMessages.isCollectionSelector(selector)) {
                var element = NativeKeywordMessages.collectionElement(project, receiver.type());
                if (element != null) return collectionMessage(message, receiver, element);
            }
            if (receiver != null && receiver.type() instanceof ResolvedType.Builtin builtin) {
                var operation = NativeKeywordMessages.temporalInstance(builtin.javaType(), selector);
                if (operation != null) return temporalMessage(message, operation, receiver);
            }
            if (receiver != null && conditional && receiver.type().equals(BOOL)) {
                require(message.receiver(), receiver, BOOL);
                var onTrue = resolve(message.parts().getFirst().argument());
                var alternative = message.parts().get(1).argument();
                var onFalse = resolve(alternative);
                if (!onTrue.type().equals(onFalse.type()) || onTrue.optional() != onFalse.optional()) {
                    throw error(alternative, "Conditional alternatives must have the same type and optionality");
                }
                return new Typed(CodeBlock.of("new $T<>($L, $L, $L)", NativeValidation.Conditional.class,
                        receiver.program(), onTrue.program(), onFalse.program()), onTrue.type(), onTrue.optional());
            }
            if (receiver != null && (round || divide) && receiver.type().equals(DECIMAL)) {
                require(message.receiver(), receiver, DECIMAL);
                var parts = message.parts();
                var scale = compileValue(parts.get(round ? 0 : 1).argument(), INT);
                var mode = NativeKeywordMessages.roundingMode(parts.getLast().argument());
                var roundingMode = CodeBlock.of("$T.$L", java.math.RoundingMode.class, mode.name());
                var code = round ? CodeBlock.of("new $T<>($L, $L, $L)", NativeValidation.RoundDecimal.class,
                        receiver.program(), scale, roundingMode)
                        : CodeBlock.of("new $T<>($L, $L, $L, $L)", NativeValidation.DivideDecimal.class,
                                receiver.program(), compileValue(parts.getFirst().argument(), DECIMAL), scale, roundingMode);
                return new Typed(code, DECIMAL, false);
            }
        }
        return call(message, MethodNode.Mode.READ);
    }

    private Typed collectionMessage(KeywordExpression.Message message, Typed receiver, ResolvedType.Declared element) {
        if (receiver.optional()) throw error(message.receiver(), "Match an optional collection before querying it");
        var block = NativeKeywordMessages.requireBlock(message.parts().getFirst().argument());
        checkBindingName(block.binding());
        var nested = new java.util.ArrayList<>(bindings);
        nested.add(new Binding(block.binding().name(), element));
        var projection = new NativeValidationCompiler(owner, project, parameters, nested, locals, invocation).resolve(block.body());
        if (message.selector().equals(NativeKeywordMessages.COLLECT)) {
            var output = NativeKeywordMessages.collectionTarget(message.parts().getLast().argument(), scopeFor(owner));
            var outputElement = NativeKeywordMessages.collectionElement(project, output);
            if (projection.optional() || !projection.type().equals(outputElement)) {
                throw error(block.body(), "Collection block must return a non-optional " + outputElement.symbol().identity().qualifiedName());
            }
            return collectionQuery(NativeValidation.CollectionOperator.COLLECT, receiver, projection, output, outputElement);
        }
        boolean sum = message.selector().equals(NativeKeywordMessages.SUM);
        boolean select = message.selector().equals(NativeKeywordMessages.SELECT);
        if (sum) require(block.body(), projection, numericType(projection.type()));
        else require(block.body(), projection, BOOL);
        var operation = select ? NativeValidation.CollectionOperator.SELECT : !sum ? NativeValidation.CollectionOperator.ANY
                : projection.type().equals(DECIMAL) ? NativeValidation.CollectionOperator.SUM_DECIMAL
                : NativeValidation.CollectionOperator.SUM_INTEGER;
        return collectionQuery(operation, receiver, projection, select ? receiver.type() : projection.type(), select ? element : null);
    }

    private Typed collectionQuery(NativeValidation.CollectionOperator operation, Typed receiver, Typed projection,
                                  ResolvedType output, ResolvedType.Declared outputElement) {
        var rebuild = outputElement == null ? CodeBlock.of("null")
                : CodeBlock.of("items -> $T.of(items.stream().map($T.class::cast).toList())",
                        ResolvedJavaTypes.javaType(output), ResolvedJavaTypes.javaType(outputElement));
        return new Typed(CodeBlock.of("new $T<>($T.$L, $L, $L, $L)", NativeValidation.CollectionQuery.class,
                NativeValidation.CollectionOperator.class, operation.name(), receiver.program(), projection.program(), rebuild),
                output, false);
    }

    private Typed temporalMessage(KeywordExpression.Message message, NativeKeywordMessages.TemporalMessage operation,
                                  Typed receiver) {
        if (receiver != null) require(message.receiver(), receiver, new ResolvedType.Builtin(operation.owner()));
        var argument = compileValue(message.parts().getFirst().argument(), new ResolvedType.Builtin(operation.argument()));
        var code = CodeBlock.of("new $T<>($T.$L, $L, $L)", NativeValidation.Temporal.class,
                NativeValidation.TemporalOperator.class, operation.operator().name(),
                receiver == null ? argument : receiver.program(), receiver == null ? CodeBlock.of("null") : argument);
        return new Typed(code, new ResolvedType.Builtin(operation.result()), false);
    }

    private record Binding(String name, ResolvedType type) { }

    private record LocalSlot(String name, ResolvedType type, boolean optional) { }

    private record Local(CodeBlock code, ResolvedType type, boolean optional) { }

    private Local binding(KeywordExpression.Name name) {
        for (int index = bindings.size() - 1; index >= 0; index--) {
            var binding = bindings.get(index);
            if (binding.name().equals(name.name())) {
                var scope = context(bindings.size() - 1 - index);
                return new Local(CodeBlock.of("$L.value()", scope), binding.type(), false);
            }
        }
        for (int index = 0; index < parameters.size(); index++) {
            var parameter = parameters.get(index);
            if (parameter.name().equals(name.name())) {
                return new Local(CodeBlock.of("$L.arguments().get($L)", context(bindings.size()), index),
                        project.typeOf(parameter.type()), false);
            }
        }
        for (int index = 0; index < locals.size(); index++) {
            var local = locals.get(index);
            if (local.name().equals(name.name())) {
                return new Local(CodeBlock.of("$L.local($L)", context(bindings.size()), index),
                        local.type(), local.optional());
            }
        }
        throw error(name, "Unknown native binding '" + name.name() + "'");
    }

    private boolean hasBinding(String name) {
        return parameters.stream().anyMatch(parameter -> parameter.name().equals(name))
                || bindings.stream().anyMatch(binding -> binding.name().equals(name))
                || locals.stream().anyMatch(local -> local.name().equals(name));
    }

    private void checkBindingName(KeywordExpression.Name binding) {
        String name = binding.name();
        if (name.equals("self") || !org.vernac.language.VernacNames.isIdentifier(name) || hasBinding(name)
                || owner instanceof UseCaseNode useCase && (name.equals("events")
                        || useCase.injections().stream().anyMatch(field -> field.name().equals(name)))) {
            throw error(binding, "Invalid or duplicate native binding '" + name + "'");
        }
    }

    private static String context(int depth) {
        return "context" + ".outer()".repeat(depth);
    }

    private Typed match(KeywordExpression.Match expression) {
        var optional = resolve(expression.subject());
        if (!optional.optional()) throw error(expression.subject(), "Match requires an optional value");
        checkBindingName(expression.binding());
        var nested = new java.util.ArrayList<>(bindings);
        nested.add(new Binding(expression.binding().name(), optional.type()));
        var present = new NativeValidationCompiler(owner, project, parameters, nested, locals, invocation)
                .resolve(expression.onPresent());
        var absent = resolve(expression.onAbsent());
        if (!present.type().equals(absent.type()) || present.optional() != absent.optional())
            throw error(expression.onAbsent(), "Match alternatives must have the same type and optionality");
        return new Typed(CodeBlock.of("$T.match($L, $L, $L)", NativeValidation.class,
                optional.program(), present.program(), absent.program()), present.type(), present.optional());
    }

    private Typed call(KeywordExpression.Message message, MethodNode.Mode effect) {
        if (owner instanceof UseCaseNode useCase) return applicationCall(useCase, message, effect);
        if (!(message.receiver() instanceof KeywordExpression.Name name) || !name.name().equals("self"))
            throw error(message, "Native keyword sends currently require self as receiver");
        return domainCall(owner, self(), message, effect);
    }

    private Typed domainCall(TopLevelDefinition receiver, CodeBlock target, KeywordExpression.Message message,
                             MethodNode.Mode effect) {
        var candidates = methods(receiver).stream()
                .filter(method -> method.mode() == effect && method.accessModifier().equals("public"))
                .filter(method -> selectorMatches(method.name(), method.parameters(), message))
                .toList();
        if (candidates.size() != 1)
            throw error(message, "Expected one " + (effect == MethodNode.Mode.READ ? "readable" : "modifying")
                    + " behavior for selector '" + message.selector() + "'");
        var method = candidates.getFirst();
        var result = project.typeOf(method.returnType());
        boolean nothing = result instanceof ResolvedType.VoidReturn;
        if (nothing && effect == MethodNode.Mode.READ)
            throw error(message, "Native keyword read must return a value");
        var args = arguments(message, method.parameters());
        var invocation = CodeBlock.of("$L.$N($L)", target, method.name(), args.values());
        var lambdaBody = nothing ? CodeBlock.of("{ $L; return true; }", invocation) : invocation;
        return callProgram(args.programs(), lambdaBody, result, method.returnType().isOptional());
    }

    private Typed applicationCall(UseCaseNode useCase, KeywordExpression.Message message, MethodNode.Mode effect) {
        if (!(message.receiver() instanceof KeywordExpression.Name name))
            throw error(message, "Native execute calls require a named port or event receiver");
        if (hasBinding(name.name())) {
            var local = binding(name);
            if (local.optional() || !(local.type() instanceof ResolvedType.Declared declared)
                    || declared.symbol().kind() != TypeSymbol.Kind.AGGREGATE
                    || !(definition(declared) instanceof AggregateNode aggregate))
                throw error(name, "Expected a non-optional aggregate receiver");
            return domainCall(aggregate, CodeBlock.of("(($T) $L)", ResolvedJavaTypes.javaType(declared),
                    local.code()), message, effect);
        }
        var injected = useCase.injections().stream().filter(field -> field.name().equals(name.name())).findFirst();
        if (injected.isEmpty() && name.name().equals("events") && message.selector().equals("emit:")) {
            if (effect != MethodNode.Mode.MODIFY) throw error(message, "Event emission must be a statement");
            var data = resolve(message.parts().getFirst().argument());
            if (data.optional() || !(data.type() instanceof ResolvedType.Declared type)
                    || type.symbol().kind() != TypeSymbol.Kind.EVENT)
                throw error(message, "Expected a Vernac event");
            var send = CodeBlock.of("{ $L.emit(($T) values.get(0)); return true; }",
                    self(), ResolvedJavaTypes.javaType(type));
            return callProgram(List.of(data.program()), send, new ResolvedType.VoidReturn(), false);
        }
        if (injected.isPresent()) {
            var type = project.typeOf(injected.orElseThrow().type());
            if (!(type instanceof ResolvedType.Declared declared))
                throw error(message, "Native execute requires a declared port or repository dependency");
            var dependency = definition(declared);
            if (dependency instanceof RepositoryNode repository)
                return repositoryCall(name, message, effect, repository);
            if (!(dependency instanceof PortNode port))
                throw error(message, "Native execute requires a declared port or repository dependency");
            var targets = port.methods().stream().filter(method -> selectorMatches(method.name(),
                    method.parameters(), message)).toList();
            if (targets.size() != 1) throw error(message, "Unknown port selector '" + message.selector() + "'");
            var selected = targets.getFirst();
            var result = project.typeOf(selected.returnType());
            boolean nothing = result instanceof ResolvedType.VoidReturn;
            if (nothing && effect == MethodNode.Mode.READ) throw error(message, "Void port calls must be statements");
            var args = arguments(message, selected.parameters());
            var send = CodeBlock.of("$L.$N().$N($L)", self(), name.name(), selected.name(), args.values());
            if (nothing) send = CodeBlock.of("{ $L; return true; }", send);
            return callProgram(args.programs(), send, result, selected.returnType().isOptional());
        }
        var scope = scopeFor(useCase);
        var candidate = scope.resolve(name.name(), name.location()).type().orElse(null);
        if (candidate instanceof ResolvedType.Declared declared && declared.symbol().kind() == TypeSymbol.Kind.EVENT
                && definition(declared) instanceof EventNode event) {
            if (!event.fields().stream().map(FieldNode::name).toList()
                    .equals(message.parts().stream().map(KeywordExpression.KeywordPart::name).toList()))
                throw error(message, "Event selector must name each declared field in order");
            var args = arguments(message, event.fields());
            return callProgram(args.programs(), CodeBlock.of("$T.create($L)",
                    ResolvedJavaTypes.javaType(declared), args.values()), declared, false);
        }
        throw error(name, "Unknown native dependency or event receiver '" + name.name() + "'");
    }

    private Typed repositoryCall(KeywordExpression.Name name, KeywordExpression.Message message,
                                 MethodNode.Mode effect, RepositoryNode repository) {
        String selector = message.selector();
        if (!Set.of("byId:", "save:", "delete:").contains(selector))
            throw error(message, "Unknown native repository selector '" + selector + "'");
        var scope = scopeFor(repository);
        var type = scope.resolve(repository.aggregateName(), repository.location()).type().orElse(null);
        if (!(type instanceof ResolvedType.Declared aggregateType)
                || !(definition(aggregateType) instanceof AggregateNode aggregate))
            throw error(message, "Repository aggregate is unavailable in this compilation");
        boolean delete = selector.equals("delete:");
        if (delete && effect == MethodNode.Mode.READ)
            throw error(message, "Repository deletion must be a statement");
        var expected = selector.equals("byId:") ? project.typeOf(aggregate.idDefinition().type()) : aggregateType;
        var parameter = compileValue(message.parts().getFirst().argument(), expected);
        var send = CodeBlock.of("$L.$N().$N(($T) values.get(0))", self(), name.name(),
                message.parts().getFirst().name(), ResolvedJavaTypes.javaType(expected));
        if (delete) send = CodeBlock.of("{ $L; return true; }", send);
        return callProgram(List.of(parameter), send,
                delete ? new ResolvedType.VoidReturn() : aggregateType, false);
    }

    private org.vernac.compiler.symbols.FileTypeScope scopeFor(TopLevelDefinition definition) {
        return project.project().sources().stream().filter(s -> s.unit().definitions().contains(definition))
                .findFirst().map(s -> project.scopes().get(s.path())).orElseThrow();
    }

    private TopLevelDefinition definition(ResolvedType.Declared type) {
        var identity = type.symbol().identity();
        return project.project().sources().stream().filter(s -> s.unit().namespace().equals(identity.namespace()))
                .flatMap(s -> s.unit().definitions().stream()).filter(node -> switch (node) {
                    case PortNode port -> port.name().equals(identity.name());
                    case RepositoryNode repository -> repository.name().equals(identity.name());
                    case EventNode event -> event.name().equals(identity.name());
                    case AggregateNode aggregate -> aggregate.name().equals(identity.name());
                    default -> false;
                }).findFirst().orElse(null);
    }

    private static boolean selectorMatches(String name, List<FieldNode> parameters, KeywordExpression.Message message) {
        return name.equals(message.parts().getFirst().name()) && parameters.size() == message.parts().size()
                && java.util.stream.IntStream.range(1, parameters.size()).allMatch(
                        index -> parameters.get(index).name().equals(message.parts().get(index).name()));
    }

    private record Arguments(List<CodeBlock> programs, CodeBlock values) { }

    private Arguments arguments(KeywordExpression.Message message, List<FieldNode> parameters) {
        var programs = new java.util.ArrayList<CodeBlock>();
        var values = new java.util.ArrayList<CodeBlock>();
        for (int index = 0; index < parameters.size(); index++) {
            var expected = project.typeOf(parameters.get(index).type());
            programs.add(compileValue(message.parts().get(index).argument(), expected));
            values.add(CodeBlock.of("($T) values.get($L)", ResolvedJavaTypes.javaType(expected), index));
        }
        return new Arguments(programs, CodeBlock.join(values, ", "));
    }

    private static Typed callProgram(List<CodeBlock> programs, CodeBlock invocation, ResolvedType type, boolean optional) {
        return new Typed(CodeBlock.of("$T.call($T.of($L), (context, values) -> $L)",
                NativeValidation.class, List.class, CodeBlock.join(programs, ", "), invocation), type, optional);
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
                var number = numericType(left.type());
                require(expression.left(), left, number);
                require(expression.right(), right, number);
                resultType = Set.of("<", ">", "<=", ">=").contains(op) ? BOOL : number;
            }
        }
        return new Typed(CodeBlock.of("$T.binary($S, $L, $L)", NativeValidation.class,
                op, left.program(), right.program()), resultType, false);
    }

    private static ResolvedType.Builtin numericType(ResolvedType type) {
        return type.equals(DECIMAL) ? DECIMAL : INT;
    }

    private Read read(KeywordExpression.UnaryMessage expression) {
        CodeBlock target;
        TopLevelDefinition definition;
        if (expression.receiver() instanceof KeywordExpression.Name name && name.name().equals("self")) {
            if (owner instanceof UseCaseNode)
                throw error(expression, "Use case inputs are named directly and dependencies use keyword messages");
            target = self();
            definition = owner;
        } else if (expression.receiver() instanceof KeywordExpression.Name name) {
            var local = binding(name);
            if (local.optional()) throw error(expression, "Match an optional value before reading its fields");
            if (owner instanceof UseCaseNode && local.type() instanceof ResolvedType.Declared declared
                    && declared.symbol().kind() == TypeSymbol.Kind.AGGREGATE
                    && definition(declared) instanceof AggregateNode aggregate) {
                definition = aggregate;
            } else {
                definition = readValueType(expression, local.type());
            }
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
                case DECIMAL -> new Typed(CodeBlock.of("$T.literal(new $T($S))", NativeValidation.class,
                        BigDecimal.class, spelling), DECIMAL, false);
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
