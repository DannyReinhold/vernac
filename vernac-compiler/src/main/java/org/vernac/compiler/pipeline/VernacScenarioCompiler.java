// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import org.antlr.v4.runtime.ParserRuleContext;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.ast.EntityNode;
import org.vernac.compiler.ast.FieldNode;
import org.vernac.compiler.ast.ImportNode;
import org.vernac.compiler.ast.MethodNode;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.ast.TopLevelDefinition;
import org.vernac.compiler.ast.TypeNode;
import org.vernac.compiler.ast.ValueObjectNode;
import org.vernac.compiler.parser.VernacParser;
import org.vernac.compiler.parser.VernacParser.*;
import org.vernac.compiler.symbols.FileTypeScope;
import org.vernac.compiler.symbols.JavaTypeNames;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.compiler.symbols.TypeSymbol;
import org.vernac.language.VernacNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles test-only domain scenarios against declarations already resolved from main sources. */
public final class VernacScenarioCompiler {
    private static final String PACKAGE = "org.vernac.generated";
    private static final String BRIDGE = "VernacScenariosTest";
    private static final ClassName ASSERTIONS = ClassName.get("org.junit.jupiter.api", "Assertions");
    private final VernacSourceParser parser = new VernacSourceParser();

    public List<Path> discover(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        if (!Files.isDirectory(root)) throw new IOException("Test source path is not a directory: " + root);
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".vernac"))
                    .sorted()
                    .toList();
        }
    }

    public int generate(Path root, List<Path> sources, ResolvedProject main, Path output) throws IOException {
        // Retain the bridge after the last case is removed so an incremental build overwrites
        // the existing test class instead of allowing Surefire to run a stale case.
        if (sources.isEmpty() && !Files.exists(output.resolve(GeneratedSourceWriter.MANIFEST))) return 0;

        Map<String, String> texts = new LinkedHashMap<>();
        for (Path path : sources) {
            String source = "src/test/vernac/" + root.relativize(path).toString().replace('\\', '/');
            texts.put(source, Files.readString(path));
        }
        var compiled = compile(texts, main);
        GeneratedSourceWriter.write(List.of(compiled.bridge()), output);
        return compiled.caseCount();
    }

    /** Checks the same source snapshots used for generation without writing generated files. */
    public List<Reference> validate(Map<String, String> sources, ResolvedProject main) {
        return compile(sources, main).references();
    }

    /** A source reference and its resolved model declaration, with a code-point width. */
    public record Reference(SourceLocation source, int width, SourceLocation declaration) { }

    private record Compilation(JavaFile bridge, int caseCount, List<Reference> references) { }

    private Compilation compile(Map<String, String> sources, ResolvedProject main) {
        var bridge = TypeSpec.classBuilder(BRIDGE);
        List<Reference> references = new ArrayList<>();
        Set<String> suites = new HashSet<>();
        Set<String> cases = new HashSet<>();
        Map<String, TopLevelDefinition> definitions = new HashMap<>();
        Set<String> knownNamespaces = new HashSet<>();
        if (!sources.isEmpty()) {
            for (var file : main.project().sources()) {
                String namespace = file.unit().namespace();
                knownNamespaces.add(namespace);
                for (var definition : file.unit().definitions()) {
                    switch (definition) {
                        case AggregateNode aggregate -> definitions.put(namespace + "." + aggregate.name(), aggregate);
                        case EntityNode entity -> definitions.put(namespace + "." + entity.name(), entity);
                        case ValueObjectNode value -> definitions.put(namespace + "." + value.name(), value);
                        default -> { }
                    }
                }
            }
        }
        for (var entry : sources.entrySet()) {
            String source = entry.getKey();
            var tree = parser.parser(source, entry.getValue()).testCompilationUnit();
            parser.validateNames(source, tree);
            String namespace = tree.namespaceDeclaration().qualifiedName().getText();
            if (!VernacNames.isNamespace(namespace))
                throw error(location(source, tree.namespaceDeclaration()), "Invalid namespace '" + namespace + "'");
            var imports = tree.importDeclaration().stream().map(node -> {
                String name = node.qualifiedName().getText();
                return new ImportNode(location(source, node), name, node.getText().endsWith(".*;"));
            }).toList();
            var unit = new CompilationUnitNode(location(source, tree), namespace, imports, List.of());
            var scope = new FileTypeScope(unit, main.project().symbols(), knownNamespaces);
            if (scope.hasErrors()) throw new SemanticValidationException(scope.diagnostics());
            for (var suite : tree.testSuite()) {
                String suiteName = suite.name.getText();
                if (!VernacNames.isTypeName(suiteName))
                    throw error(location(source, suite.name), VernacNames.invalidNameMessage(suiteName));
                String suiteId = namespace + "." + suiteName;
                if (!suites.add(suiteId))
                    throw error(location(source, suite), "Duplicate test suite '" + suiteId + "'");
                for (var scenario : suite.testScenario()) {
                    String scenarioName = scenario.name.getText();
                    if (!VernacNames.isIdentifier(scenarioName))
                        throw error(location(source, scenario.name), VernacNames.invalidNameMessage(scenarioName));
                    String id = suiteId + "." + scenarioName;
                    if (!cases.add(id)) throw error(location(source, scenario), "Duplicate scenario '" + id + "'");
                    bridge.addMethod(new Scenario(source, id, scope, main, definitions, references).compile(scenario));
                }
            }
        }
        return new Compilation(JavaFile.builder(PACKAGE, bridge.build()).indent("    ").build(),
                cases.size(), List.copyOf(references));
    }

    private record Expression(CodeBlock code, ResolvedType type, boolean optional) {
        Expression(CodeBlock code, ResolvedType type) {
            this(code, type, false);
        }
    }

    private record Scenario(String source, String id, FileTypeScope scope, ResolvedProject main,
                            Map<String, TopLevelDefinition> definitions, List<Reference> references) {
        MethodSpec compile(TestScenarioContext scenario) {
            var method = MethodSpec.methodBuilder(id.replace("$", "$s").replace(".", "$d"))
                    .addAnnotation(ClassName.get("org.junit.jupiter.api", "Test"))
                    .addAnnotation(AnnotationSpec.builder(ClassName.get("org.junit.jupiter.api", "DisplayName"))
                            .addMember("value", "$S", id).build());
            Map<String, Expression> fixtures = new HashMap<>();
            method.addStatement("String statement = $S", location(source, scenario).toString());
            method.beginControlFlow("try");
            for (var node : scenario.children) {
                if (!(node instanceof ParserRuleContext statement)) continue;
                String message = location(source, statement) + " [" + id + "]";
                method.addStatement("statement = $S", message);
                if (statement instanceof TestStepContext step && step.fixture != null) {
                    bind(method, fixtures, step.fixture, expression(step.value, fixtures));
                } else if (statement instanceof TestStepContext step) {
                    var receiver = fixtures.get(step.receiver.getText());
                    if (receiver == null)
                        throw error(location(source, step.receiver), "Unknown fixture '" + step.receiver.getText() + "'");
                    String action = step.action.getText();
                    var target = methods(definition(receiver.type())).stream()
                            .filter(candidate -> candidate.name().equals(action) && "public".equals(candidate.accessModifier()))
                            .findFirst()
                            .orElseThrow(() -> error(location(source, step.action), "Unknown public behavior '" + action + "'"));
                    reference(step.action, target.location());
                    var args = arguments(step, step.testArguments(), target.parameters(), fixtures);
                    var invocation = CodeBlock.of("$L.$L($L)", receiver.code(), action, args);
                    if (step.result == null) {
                        method.addStatement("$L", invocation);
                    } else {
                        var resultType = main.typeOf(target.returnType());
                        if (resultType instanceof ResolvedType.VoidReturn)
                            throw error(location(source, step.result), "Behavior '" + action + "' does not return a value");
                        bind(method, fixtures, step.result,
                                new Expression(invocation, resultType, target.returnType().isOptional()));
                    }
                } else if (statement instanceof TestAssertionContext assertion) {
                    var actual = expression(assertion.actual, fixtures);
                    if (assertion.expected != null) {
                        var expected = expression(assertion.expected, fixtures);
                        requireType(assertion.expected, expected, actual.type(), actual.optional());
                        method.addStatement("$T.assertEquals((Object) $L, (Object) $L, statement)",
                                ASSERTIONS, expected.code(), actual.code());
                    } else if (assertion.outcome.getText().equals("invalid")) {
                        if (assertion.actual.factory == null)
                            throw error(location(source, assertion.actual), "Expected a constructor in an invalid-value assertion");
                        method.addStatement("$T.assertThrows($T.class, () -> $L, statement)",
                                ASSERTIONS, org.vernac.runtime.DomainValidationException.class, actual.code());
                    } else {
                        boolean present = assertion.outcome.getText().equals("present");
                        if (actual.optional()) {
                            method.addStatement("$T.$L($L.isPresent(), statement)", ASSERTIONS,
                                    present ? "assertTrue" : "assertFalse", actual.code());
                        } else {
                            method.addStatement("$T.$L($L, statement)", ASSERTIONS,
                                    present ? "assertNotNull" : "assertNull", actual.code());
                        }
                    }
                }
            }
            method.nextControlFlow("catch ($T failure)", RuntimeException.class)
                    .addStatement("throw new $T(statement, failure)", AssertionError.class)
                    .endControlFlow();
            return method.build();
        }

        private void bind(MethodSpec.Builder method, Map<String, Expression> fixtures,
                          ParserRuleContext binding, Expression value) {
            String fixture = binding.getText();
            if (!VernacNames.isIdentifier(fixture))
                throw error(location(source, binding), VernacNames.invalidNameMessage(fixture));
            if (fixtures.containsKey(fixture))
                throw error(location(source, binding), "Duplicate fixture '" + fixture + "'");
            CodeBlock name = CodeBlock.of("$L", fixtureName(fixture));
            method.addStatement("var $L = $L", name, value.code());
            fixtures.put(fixture, new Expression(name, value.type(), value.optional()));
        }

        private Expression expression(TestExpressionContext value, Map<String, Expression> fixtures) {
            if (value.literal != null) return literal(value);
            if (value.factory != null) {
                var type = resolve(value.factory.getText(), value.factory);
                reference(value.factory, type.symbol().location());
                var definition = definition(type);
                var factory = switch (definition) {
                    case AggregateNode ignored -> "create";
                    case EntityNode ignored -> "create";
                    case ValueObjectNode object when !object.isEnum() -> "of";
                    case null, default -> throw error(location(source, value),
                            "No fixture constructor for '" + value.factory.getText() + "'");
                };
                return new Expression(CodeBlock.of("$L.$L($L)", JavaTypeNames.canonicalName(type), factory,
                        arguments(value, value.testArguments(), fields(definition), fixtures)), type);
            }

            var parts = value.reference.qualifiedNameSegment();
            var names = parts.stream().map(ParserRuleContext::getText).toList();
            var fixture = fixtures.get(names.getFirst());
            if (fixture != null) {
                var result = fixture;
                for (var part : parts.subList(1, parts.size())) {
                    String field = part.getText();
                    var definition = definition(result.type());
                    TypeNode type = result.optional() ? null : fieldType(definition, field);
                    if (type == null) throw error(location(source, part), "Unknown field '" + field + "'");
                    reference(part, type.location());
                    result = new Expression(CodeBlock.of("$L.$L()", result.code(), field),
                            main.typeOf(type), type.isOptional());
                }
                return result;
            }

            if (names.size() == 1) throw error(location(source, value), "Unknown fixture '" + names.getFirst() + "'");
            var type = resolve(String.join(".", names.subList(0, names.size() - 1)), value);
            String member = names.getLast();
            if (!(definition(type) instanceof ValueObjectNode enumeration))
                throw error(location(source, value), "Expected an enum member or a fixture field path");
            var constant = enumeration.enumConstants().stream()
                    .filter(candidate -> candidate.name().equals(member))
                    .findFirst()
                    .orElseThrow(() -> error(location(source, parts.getLast()), "Unknown enum member '" + value.getText() + "'"));
            reference(parts.getLast(), constant.location());
            return new Expression(CodeBlock.of("$L.$L", JavaTypeNames.canonicalName(type), member), type);
        }

        private void reference(ParserRuleContext node, SourceLocation declaration) {
            int width = node.getStop().getStopIndex() - node.getStart().getStartIndex() + 1;
            if (node.getStart().getLine() == node.getStop().getLine())
                references.add(new Reference(location(source, node), width, declaration));
        }

        private Expression literal(TestExpressionContext value) {
            String literal = value.literal.getText();
            try {
                return switch (value.literal.getType()) {
                    case VernacParser.STRING_LITERAL -> new Expression(CodeBlock.of("$S",
                            literal.substring(1, literal.length() - 1).translateEscapes()),
                            new ResolvedType.Builtin(String.class));
                    case VernacParser.INT_LITERAL -> new Expression(CodeBlock.of("$L", Integer.parseInt(literal)),
                            new ResolvedType.Builtin(int.class));
                    default -> new Expression(CodeBlock.of("$L", literal), new ResolvedType.Builtin(boolean.class));
                };
            } catch (IllegalArgumentException e) {
                throw error(location(source, value), "Invalid test literal '" + literal + "'");
            }
        }

        private ResolvedType.Declared resolve(String name, ParserRuleContext context) {
            var lookup = scope.resolve(name, location(source, context));
            if (lookup.type().isEmpty()) throw new SemanticValidationException(lookup.diagnostics());
            if (lookup.type().get() instanceof ResolvedType.Declared declared) return declared;
            throw error(location(source, context), "Expected a declared Vernac domain type, found '" + name + "'");
        }

        private TopLevelDefinition definition(ResolvedType type) {
            return type instanceof ResolvedType.Declared declared
                    ? definitions.get(declared.symbol().identity().qualifiedName()) : null;
        }

        private CodeBlock arguments(ParserRuleContext call, TestArgumentsContext args, List<FieldNode> parameters,
                                    Map<String, Expression> fixtures) {
            var values = args == null ? List.<TestExpressionContext>of() : args.testExpression();
            if (values.size() != parameters.size())
                throw error(location(source, call), "Expected " + parameters.size() + " argument(s), got " + values.size());
            var expressions = new ArrayList<CodeBlock>();
            for (int index = 0; index < values.size(); index++) {
                var value = values.get(index);
                var actual = expression(value, fixtures);
                // Constructors and behaviors accept the underlying value for optional parameters.
                requireType(value, actual, main.typeOf(parameters.get(index).type()), false);
                expressions.add(actual.code());
            }
            return expressions.stream().collect(CodeBlock.joining(", "));
        }

        private void requireType(ParserRuleContext value, Expression actual, ResolvedType expected, boolean optional) {
            if (!actual.type().equals(expected) || actual.optional() != optional) {
                throw error(location(source, value), "Expected " + describe(expected, optional)
                        + ", got " + describe(actual.type(), actual.optional()));
            }
        }
    }

    private static String describe(ResolvedType type, boolean optional) {
        String name = type instanceof ResolvedType.Declared declared
                ? declared.symbol().identity().qualifiedName() : JavaTypeNames.canonicalName(type);
        return optional ? name + "?" : name;
    }

    private static TypeNode fieldType(TopLevelDefinition definition, String name) {
        if ("id".equals(name)) {
            if (definition instanceof AggregateNode aggregate) return aggregate.idDefinition().type();
            if (definition instanceof EntityNode entity) return entity.idDefinition().type();
        }
        return fields(definition).stream().filter(field -> field.name().equals(name))
                .map(FieldNode::type).findFirst().orElse(null);
    }

    private static List<FieldNode> fields(TopLevelDefinition definition) {
        return switch (definition) {
            case AggregateNode aggregate -> aggregate.fields();
            case EntityNode entity -> entity.fields();
            case ValueObjectNode object -> object.fields();
            case null, default -> List.of();
        };
    }

    private static List<MethodNode> methods(TopLevelDefinition definition) {
        return switch (definition) {
            case AggregateNode aggregate -> aggregate.methods();
            case EntityNode entity -> entity.methods();
            case ValueObjectNode object -> object.methods();
            case null, default -> List.of();
        };
    }

    private static String fixtureName(String name) {
        return "fixture$" + name;
    }

    private static SourceLocation location(String source, ParserRuleContext node) {
        var token = node.getStart();
        return new SourceLocation(source, token.getLine(), token.getCharPositionInLine() + 1);
    }

    private static SemanticValidationException error(SourceLocation location, String message) {
        return new SemanticValidationException(List.of(CompilerDiagnostic.error(location, message)));
    }
}
