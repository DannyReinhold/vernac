// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.ast.EntityNode;
import org.vernac.compiler.ast.EventNode;
import org.vernac.compiler.ast.FieldNode;
import org.vernac.compiler.ast.ImportNode;
import org.vernac.compiler.ast.MethodNode;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.ast.KeywordExpression;
import org.vernac.compiler.ast.TopLevelDefinition;
import org.vernac.compiler.ast.TypeNode;
import org.vernac.compiler.ast.ValueObjectNode;
import org.vernac.compiler.ast.UseCaseNode;
import org.vernac.compiler.ast.PortNode;
import org.vernac.compiler.ast.InboundHttpAdapterNode;
import org.vernac.compiler.symbols.BuiltinTypes;
import org.vernac.runtime.DomainEvent;
import org.vernac.runtime.events.EventHandler;
import org.vernac.runtime.authorization.AuthorizationException;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;
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
        return generate(root, sources, main, output, null);
    }

    public int generate(Path root, List<Path> sources, ResolvedProject main, Path output, String profile) throws IOException {
        return generateSelected(root, sources, main, output, profile, null);
    }

    /** Emits only the requested case IDs in the selected profile. A null selection keeps the full profile. */
    public int generateSelected(Path root, List<Path> sources, ResolvedProject main, Path output,
                                String profile, Set<String> caseIds) throws IOException {
        // Retain the bridge after the last case is removed so an incremental build overwrites
        // the existing test class instead of allowing Surefire to run a stale case.
        if (sources.isEmpty() && profile == null && caseIds == null && !Files.exists(output.resolve(GeneratedSourceWriter.MANIFEST))) return 0;

        Map<String, String> texts = new LinkedHashMap<>();
        for (Path path : sources) {
            String source = "src/test/vernac/" + root.relativize(path).toString().replace('\\', '/');
            texts.put(source, Files.readString(path));
        }
        var compiled = compile(texts, main, profile, false, caseIds);
        if (caseIds == null && profile != null && compiled.caseCount() == 0)
            throw error(new SourceLocation(root.toString(), 0, 0), "No Vernac scenarios match profile '" + profile + "'");
        GeneratedSourceWriter.write(List.of(compiled.bridge()), output);
        return compiled.caseCount();
    }

    /** Checks the same source snapshots used for generation without writing generated files. */
    public List<Reference> validate(Map<String, String> sources, ResolvedProject main) {
        return compile(sources, main, null, true, null).references();
    }

    /** A source reference and its resolved model declaration, with a code-point width. */
    public record Reference(SourceLocation source, int width, SourceLocation declaration) { }

    private record Compilation(JavaFile bridge, int caseCount, List<Reference> references) { }

    private Compilation compile(Map<String, String> sources, ResolvedProject main, String selectedProfile, boolean allProfiles, Set<String> caseIds) {
        var bridge = TypeSpec.classBuilder(BRIDGE);
        List<Reference> references = new ArrayList<>();
        Set<String> suites = new HashSet<>();
        Set<String> cases = new HashSet<>();
        var remaining = caseIds == null ? null : new java.util.TreeSet<>(caseIds);
        int executed = 0;
        var model = model(main);
        for (var entry : sources.entrySet()) {
            String source = entry.getKey();
            var tree = parser.parser(source, entry.getValue()).testCompilationUnit();
            parser.validateNames(source, tree);
            String namespace = tree.namespaceDeclaration().qualifiedName().getText();
            if (!VernacNames.isNamespace(namespace))
                throw error(location(source, tree.namespaceDeclaration()), "Invalid namespace '" + namespace + "'");
            var scope = scope(source, tree, namespace, main, model);
            if (scope.hasErrors()) throw new SemanticValidationException(scope.diagnostics());
            for (var suite : tree.testSuite()) {
                String suiteName = suite.name.getText();
                if (!VernacNames.isTypeName(suiteName))
                    throw error(location(source, suite.name), VernacNames.invalidNameMessage(suiteName));
                String suiteId = namespace + "." + suiteName;
                if (!suites.add(suiteId))
                    throw error(location(source, suite), "Duplicate test suite '" + suiteId + "'");
                String configuration = suite.configuration == null ? null : suite.configuration.getText();
                if (configuration != null && (!VernacNames.isNamespace(configuration) || !configuration.contains(".")))
                    throw error(location(source, suite.configuration), "Expected the qualified name of a Java test configuration");
                String profile = suite.profileName == null ? "domain" : suite.profileName.getText();
                if (!Set.of("domain", "mvc", "postgres").contains(profile))
                    throw error(location(source, suite.profileName), "Unknown scenario profile '" + profile + "'");
                if (!profile.equals("domain") && configuration == null)
                    throw error(location(source, suite), "Profile '" + profile + "' requires a suite test configuration");
                boolean selected = allProfiles || (selectedProfile == null ? !profile.equals("postgres") : selectedProfile.equals(profile));
                for (var scenario : suite.testScenario()) {
                    String scenarioName = scenario.name.getText();
                    if (!VernacNames.isIdentifier(scenarioName))
                        throw error(location(source, scenario.name), VernacNames.invalidNameMessage(scenarioName));
                    String id = suiteId + "." + scenarioName;
                    if (!cases.add(id)) throw error(location(source, scenario), "Duplicate scenario '" + id + "'");
                    var test = new Scenario(source, id, scope, main, model.definitions(), references, configuration, profile).compile(scenario);
                    if (selected && (remaining == null || remaining.remove(id))) {
                        bridge.addMethod(test);
                        executed++;
                    }
                }
            }
        }
        if (remaining != null && !remaining.isEmpty())
            throw error(SourceLocation.UNKNOWN, "Vernac scenarios not found in the selected profiles: " + remaining);
        return new Compilation(JavaFile.builder(PACKAGE, bridge.build()).indent("    ").build(),
                executed, List.copyOf(references));
    }

    private record Model(Map<String, TopLevelDefinition> definitions, Set<String> namespaces) { }

    private static Model model(ResolvedProject main) {
        Map<String, TopLevelDefinition> definitions = new HashMap<>();
        Set<String> namespaces = new HashSet<>();
        for (var file : main.project().sources()) {
            String namespace = file.unit().namespace();
            namespaces.add(namespace);
            for (var definition : file.unit().definitions()) {
                String name = switch (definition) {
                    case AggregateNode aggregate -> aggregate.name();
                    case EntityNode entity -> entity.name();
                    case ValueObjectNode value -> value.name();
                    case InboundHttpAdapterNode adapter -> adapter.name();
                    case PortNode port -> port.name();
                    case UseCaseNode useCase -> useCase.name();
                    case EventNode event -> event.name();
                    default -> null;
                };
                if (name != null) definitions.put(namespace + "." + name, definition);
            }
        }
        return new Model(definitions, namespaces);
    }

    private static FileTypeScope scope(String source, TestCompilationUnitContext tree, String namespace,
                                       ResolvedProject main, Model model) {
        var imports = tree.importDeclaration().stream().map(node ->
                new ImportNode(location(source, node), node.qualifiedName().getText(), node.getText().endsWith(".*;"))).toList();
        var unit = new CompilationUnitNode(location(source, tree), namespace, imports, List.of());
        return new FileTypeScope(unit, main.project().symbols(), model.namespaces());
    }

    /** Completion answers replace the current source prefix, including its qualifier. */
    public record Completion(int start, List<String> candidates) { }

    public Completion complete(String source, String text, int offset, ResolvedProject main) {
        if (offset < 0 || offset > text.length()) return new Completion(0, List.of());
        String before = text.substring(0, offset);
        var lexer = new VernacLexer(CharStreams.fromString(before));
        lexer.removeErrorListeners();
        int depth = 0;
        int start = 0;
        for (Token token : lexer.getAllTokens()) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            switch (token.getText()) {
                case "{" -> depth++;
                case "}" -> depth--;
                case ";" -> { }
                default -> { continue; }
            }
            start = before.offsetByCodePoints(0, token.getStopIndex() + 1);
        }
        if (depth != 2) return new Completion(offset, List.of());
        var match = java.util.regex.Pattern.compile(
                "(?s).*?\\b(invoke|request|action|emitted|calls\\s+to|expect|equals|when)\\s+([\\p{javaJavaIdentifierPart}.]*)$").matcher(before.substring(start));
        if (!match.matches()) return new Completion(offset, List.of());
        String kind = match.group(1).replaceAll("\\s+", " ");
        String prefix = match.group(2);
        int replacement = offset - prefix.length();

        try {
            var tree = parser.parser(source, before.substring(0, start) + " expect true equals true; }}")
                    .testCompilationUnit();
            var suite = tree.testSuite().getLast();
            var current = suite.testScenario().getLast();
            String namespace = tree.namespaceDeclaration().qualifiedName().getText();
            var model = model(main);
            var scope = scope(source, tree, namespace, main, model);
            if (scope.hasErrors()) return new Completion(replacement, List.of());
            var scenario = new Scenario(source, "completion", scope, main, model.definitions(), new ArrayList<>(),
                    suite.configuration == null ? null : suite.configuration.getText(),
                    suite.profileName == null ? "domain" : suite.profileName.getText());
            var candidates = switch (kind) {
                case "invoke", "request", "action", "emitted", "calls to" ->
                        scenario.completeDeclaration(kind, prefix, current);
                default -> scenario.completeFixture(kind, prefix, current);
            };
            return new Completion(replacement, candidates.stream().filter(name -> name.startsWith(prefix)).distinct().sorted().toList());
        } catch (SemanticValidationException invalidModel) {
            return new Completion(replacement, List.of());
        }
    }

    private record Expression(CodeBlock code, ResolvedType type, boolean optional) {
        Expression(CodeBlock code, ResolvedType type) {
            this(code, type, false);
        }
    }

    private record Scenario(String source, String id, FileTypeScope scope, ResolvedProject main,
                            Map<String, TopLevelDefinition> definitions, List<Reference> references, String configuration, String profile) {
        MethodSpec compile(TestScenarioContext scenario) {
            var method = MethodSpec.methodBuilder(id.replace("$", "$s").replace(".", "$d"))
                    .addAnnotation(ClassName.get("org.junit.jupiter.api", "Test"))
                    .addAnnotation(AnnotationSpec.builder(ClassName.get("org.junit.jupiter.api", "Tag"))
                            .addMember("value", "$S", profile).build())
                    .addAnnotation(AnnotationSpec.builder(ClassName.get("org.junit.jupiter.api", "DisplayName"))
                            .addMember("value", "$S", id).build());
            Map<String, Expression> fixtures = new HashMap<>();
            var observations = eventObservations(scenario);
            var portCalls = portObservations(scenario);
            method.addStatement("String statement = $S", location(source, scenario).toString());
            method.beginControlFlow("try");
            if (configuration != null) {
                var context = ClassName.get("org.springframework.context.annotation", "AnnotationConfigApplicationContext");
                method.beginControlFlow("try (var spring = new $T())", context);
                method.addStatement("spring.register($L.class)", configuration);
                for (var port : portCalls.values().stream().distinct().toList()) registerPortCounter(method, port);
                for (var observation : observations.values().stream().distinct().toList()) registerEventCounter(method, observation);
                method.addStatement("spring.refresh()");
                for (var port : portCalls.values().stream().distinct().toList()) {
                    method.addStatement("$T.assertFalse(spring.getBeanProvider($L.class).stream().findAny().isEmpty(), $S)",
                            ASSERTIONS, port.javaType(), id + ": Missing Spring port bean " + port.javaType());
                }
                method.addStatement("var tenant = spring.getBeanProvider($T.class).getIfAvailable()", TenantId.class);
                method.beginControlFlow("try (var scope = tenant == null ? null : $T.openVerified(tenant))", TenantContext.class);
            }
            int response = 0;
            for (var node : scenario.children) {
                if (!(node instanceof ParserRuleContext statement)) continue;
                String message = location(source, statement) + " [" + id + "]";
                method.addStatement("statement = $S", message);
                if (statement instanceof TestStepContext step && step.fixture != null) {
                    bind(method, fixtures, step.fixture, expression(step.value, fixtures));
                } else if (statement instanceof TestStepContext step && step.endpoint != null) {
                    request(method, fixtures, step, ++response);
                } else if (statement instanceof TestStepContext step && step.operation != null) {
                    invoke(method, fixtures, step);
                } else if (statement instanceof TestStepContext step && step.keywordAction != null) {
                    keywordAction(method, fixtures, step);
                } else if (statement instanceof TestStepContext step) {
                    behavior(method, fixtures, step);
                } else if (statement instanceof TestAssertionContext assertion) {
                    if (assertion.responseStatus != null || assertion.responseText != null || assertion.actionEndpoint != null) {
                        assertResponse(method, fixtures, assertion, response);
                        continue;
                    }
                    if (assertion.eventType != null || assertion.portMethod != null) {
                        var expected = assertion.eventCount != null ? assertion.eventCount : assertion.portCount;
                        int count;
                        try {
                            count = Integer.parseInt(expected.getText());
                        } catch (NumberFormatException failure) {
                            throw error(location(source, assertion), "Count exceeds the supported integer range");
                        }
                        var observedCount = assertion.eventType == null
                                ? CodeBlock.of("$L.get()", portCalls.get(assertion.portMethod.getText()).counter())
                                : eventCount(assertion, observations.get(assertion.eventType.getText()), fixtures);
                        method.addStatement("$T.assertEquals((long) $L, (long) $L, statement)", ASSERTIONS, count, observedCount);
                        continue;
                    }
                    if (assertion.predicate != null) {
                        var predicate = KeywordExpressionParser.build(source, assertion.predicate);
                        var result = predicateExpression(predicate, fixtures);
                        requireScalar(predicate, result, boolean.class);
                        method.addStatement("$T.assertTrue($L, statement)", ASSERTIONS, result.code());
                        continue;
                    }
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
            if (configuration != null) method.endControlFlow().endControlFlow();
            method.nextControlFlow("catch ($T failure)", profile.equals("mvc") ? Exception.class : RuntimeException.class)
                    .addStatement("throw new $T(statement, failure)", AssertionError.class)
                    .endControlFlow();
            return method.build();
        }

        private void behavior(MethodSpec.Builder method, Map<String, Expression> fixtures, TestStepContext step) {
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
        }

        private List<String> completeDeclaration(String kind, String prefix, ParserRuleContext at) {
            var options = new ArrayList<String>();
            for (var entry : definitions.entrySet()) {
                String identity = entry.getKey();
                String shortName = identity.substring(identity.lastIndexOf('.') + 1);
                var visible = scope.resolve(shortName, location(source, at)).type().orElse(null);
                String name = visible instanceof ResolvedType.Declared declared
                        && declared.symbol().identity().qualifiedName().equals(identity)
                        && !prefix.startsWith(declared.symbol().identity().namespace() + ".") ? shortName : identity;
                switch (entry.getValue()) {
                    case UseCaseNode ignored when kind.equals("invoke") -> options.add(name);
                    case EventNode ignored when kind.equals("emitted") -> options.add(name);
                    case InboundHttpAdapterNode adapter when adapter.mvc() && (kind.equals("request") || kind.equals("action")) ->
                            adapter.endpoints().forEach(endpoint -> options.add(name + "." + endpoint.name()));
                    case PortNode port when kind.equals("calls to") ->
                            port.methods().forEach(method -> options.add(name + "." + method.name()));
                    default -> { }
                }
            }
            options.removeIf(option -> {
                String candidate = kind.equals("request") || kind.equals("action") || kind.equals("calls to")
                        ? option.substring(0, option.lastIndexOf('.')) : option;
                return scope.resolve(candidate, location(source, at)).type().isEmpty();
            });
            return options;
        }

        private List<String> completeFixture(String kind, String prefix, TestScenarioContext current) {
            Map<String, Expression> fixtures = new HashMap<>();
            var scratch = MethodSpec.methodBuilder("bindings");
            for (var step : current.testStep()) {
                if (step.fixture != null) bind(scratch, fixtures, step.fixture, expression(step.value, fixtures));
                else if (step.operation != null) invoke(scratch, fixtures, step);
                else if (step.keywordAction != null) keywordAction(scratch, fixtures, step);
                else if (step.receiver != null) behavior(scratch, fixtures, step);
            }

            int lastDot = prefix.lastIndexOf('.');
            if (lastDot == -1) return List.copyOf(fixtures.keySet());
            String base = prefix.substring(0, lastDot);
            String[] segments = base.split("\\.");
            var fixture = fixtures.get(segments[0]);
            if (fixture == null || fixture.optional()) return List.of();
            var definition = definition(fixture.type());
            for (int i = 1; i < segments.length; i++) {
                var field = fieldType(definition, segments[i]);
                if (field == null || field.isOptional()) return List.of();
                definition = definition(main.typeOf(field));
            }
            var names = new ArrayList<String>();
            if (kind.equals("when")) {
                methods(definition).stream().filter(method -> method.accessModifier().equals("public"))
                        .forEach(method -> names.add(base + "." + method.name()));
            } else {
                fields(definition).forEach(field -> names.add(base + "." + field.name()));
                if (definition instanceof AggregateNode || definition instanceof EntityNode) names.add(base + ".id");
            }
            return names;
        }

        private record EventObservation(String events, String javaType, EventNode definition) { }

        private Map<String, EventObservation> eventObservations(TestScenarioContext scenario) {
            Map<String, EventObservation> byIdentity = new LinkedHashMap<>();
            Map<String, EventObservation> byReference = new LinkedHashMap<>();
            for (var assertion : scenario.testAssertion()) {
                if (assertion.eventType == null) continue;
                if (configuration == null)
                    throw error(location(source, assertion), "Observing events requires a suite test configuration");
                String name = assertion.eventType.getText();
                var type = resolve(name, assertion.eventType);
                if (!(definition(type) instanceof EventNode event))
                    throw error(location(source, assertion.eventType), "Expected an event declaration");
                reference(assertion.eventType, type.symbol().location());
                String identity = type.symbol().identity().qualifiedName();
                byReference.put(name, byIdentity.computeIfAbsent(identity, key ->
                        new EventObservation("events" + byIdentity.size(), JavaTypeNames.canonicalName(type), event)));
            }
            return byReference;
        }

        private void registerEventCounter(MethodSpec.Builder method, EventObservation event) {
            String events = event.events();
            var eventType = com.palantir.javapoet.ParameterizedTypeName.get(ClassName.get(Class.class),
                    com.palantir.javapoet.WildcardTypeName.subtypeOf(DomainEvent.class));
            var handler = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(EventHandler.class)
                    .addMethod(MethodSpec.methodBuilder("listenerId").addAnnotation(Override.class)
                            .addModifiers(javax.lang.model.element.Modifier.PUBLIC).returns(String.class)
                            .addStatement("return $S", id + "/" + events).build())
                    .addMethod(MethodSpec.methodBuilder("eventClass").addAnnotation(Override.class)
                            .addModifiers(javax.lang.model.element.Modifier.PUBLIC).returns(eventType)
                            .addStatement("return $L.class", event.javaType()).build())
                    .addMethod(MethodSpec.methodBuilder("handle").addAnnotation(Override.class)
                            .addModifiers(javax.lang.model.element.Modifier.PUBLIC).addParameter(DomainEvent.class, "event")
                            .addStatement("$L.add(($L) event)", events, event.javaType()).build()).build();
            method.addStatement("var $L = new $T<$L>()", events, ArrayList.class, event.javaType());
            method.addStatement("spring.getBeanFactory().registerSingleton($S, $L)", id + "/" + events, handler);
        }

        private CodeBlock eventCount(TestAssertionContext assertion, EventObservation observation,
                                     Map<String, Expression> fixtures) {
            if (assertion.eventField == null) return CodeBlock.of("$L.size()", observation.events());
            String name = assertion.eventField.getText();
            var field = observation.definition().fields().stream().filter(candidate -> candidate.name().equals(name))
                    .findFirst().orElseThrow(() -> error(location(source, assertion.eventField), "Unknown event field '" + name + "'"));
            reference(assertion.eventField, field.location());
            var expected = expression(assertion.eventValue, fixtures);
            requireType(assertion.eventValue, expected, main.typeOf(field.type()), field.type().isOptional());
            return CodeBlock.of("$L.stream().filter(event -> $T.equals(event.$L(), $L)).count()",
                    observation.events(), java.util.Objects.class, name, expected.code());
        }

        private record PortObservation(String counter, String javaType, String method) { }

        private Map<String, PortObservation> portObservations(TestScenarioContext scenario) {
            Map<String, PortObservation> byIdentity = new LinkedHashMap<>();
            Map<String, PortObservation> byReference = new LinkedHashMap<>();
            for (var assertion : scenario.testAssertion()) {
                if (assertion.portMethod == null) continue;
                if (configuration == null)
                    throw error(location(source, assertion), "Observing ports requires a suite test configuration");
                String name = assertion.portMethod.getText();
                int split = name.lastIndexOf('.');
                if (split < 0) throw error(location(source, assertion.portMethod), "Expected Port.operation");
                var type = resolve(name.substring(0, split), assertion.portMethod);
                if (!(definition(type) instanceof PortNode port))
                    throw error(location(source, assertion.portMethod), "Expected a port declaration");
                String operation = name.substring(split + 1);
                var target = port.methods().stream().filter(candidate -> candidate.name().equals(operation))
                        .findFirst().orElseThrow(() -> error(location(source, assertion.portMethod), "Unknown port operation '" + operation + "'"));
                var segments = assertion.portMethod.qualifiedNameSegment();
                reference(segments.getLast(), target.location());
                String identity = type.symbol().identity().qualifiedName() + "." + operation;
                byReference.put(name, byIdentity.computeIfAbsent(identity, key -> new PortObservation(
                        "portCalls" + byIdentity.size(), JavaTypeNames.canonicalName(type), operation)));
            }
            return byReference;
        }

        private void registerPortCounter(MethodSpec.Builder method, PortObservation port) {
            String counter = port.counter();
            var processorType = ClassName.get("org.springframework.beans.factory.config", "BeanPostProcessor");
            var proxyType = ClassName.get("org.springframework.aop.framework", "ProxyFactory");
            var interceptor = ClassName.get("org.aopalliance.intercept", "MethodInterceptor");
            var processor = TypeSpec.anonymousClassBuilder("").addSuperinterface(processorType)
                    .addMethod(MethodSpec.methodBuilder("postProcessAfterInitialization")
                            .addAnnotation(Override.class).addModifiers(javax.lang.model.element.Modifier.PUBLIC)
                            .returns(Object.class).addParameter(Object.class, "bean").addParameter(String.class, "name")
                            .addStatement("if (!($L.class.isInstance(bean))) return bean", port.javaType())
                            .addStatement("var proxy = new $T(bean)", proxyType)
                            .beginControlFlow("proxy.addAdvice(($T) invocation ->", interceptor)
                            .addStatement("if ($S.equals(invocation.getMethod().getName())) $L.incrementAndGet()", port.method(), counter)
                            .addStatement("return invocation.proceed()")
                            .endControlFlow(")")
                            .addStatement("return proxy.getProxy()")
                            .build()).build();
            method.addStatement("var $L = new $T()", counter, java.util.concurrent.atomic.AtomicInteger.class);
            method.addStatement("spring.getBeanFactory().addBeanPostProcessor($L)", processor);
        }

        private record Route(String verb, String template, List<InboundHttpAdapterNode.Binding> bindings) { }

        private Route route(QualifiedNameContext name) {
            String qualified = name.getText();
            int split = qualified.lastIndexOf('.');
            if (split < 0) throw error(location(source, name), "Expected Adapter.endpoint");
            var type = resolve(qualified.substring(0, split), name);
            if (!(definition(type) instanceof InboundHttpAdapterNode adapter) || !adapter.mvc())
                throw error(location(source, name), "Expected a Vernac MVC adapter");
            String endpoint = qualified.substring(split + 1);
            var target = adapter.endpoints().stream().filter(candidate -> candidate.name().equals(endpoint))
                    .findFirst().orElseThrow(() -> error(location(source, name), "Unknown MVC endpoint '" + endpoint + "'"));
            reference(name.qualifiedNameSegment().getLast(), target.location());
            String base = adapter.base();
            String template = base.equals("/") ? target.route() : base + (target.route().equals("/") ? "" : target.route());
            return new Route(target.verb(), template, target.bindings());
        }

        private Map<String, CodeBlock> requestBindings(Route route, List<TestBindingContext> inputs,
                                                        Map<String, Expression> fixtures, boolean action) {
            Map<String, CodeBlock> values = new LinkedHashMap<>();
            for (var input : inputs) {
                String name = input.name.getText();
                var binding = route.bindings().stream().filter(candidate -> candidate.name().equals(name))
                        .findFirst().orElseThrow(() -> error(location(source, input.name), "Unknown MVC input '" + name + "'"));
                if (action && !binding.kind().equals("path"))
                    throw error(location(source, input.name), "Expected a route path variable for the form action");
                var expression = expression(input.value, fixtures);
                var type = main.types().get(binding.type());
                if (type == null) type = BuiltinTypes.find(binding.type().name()).map(ResolvedType.Builtin::new)
                        .orElseThrow(() -> error(location(source, input), "Unsupported MVC input type '" + binding.type().name() + "'"));
                CodeBlock value;
                if (type instanceof ResolvedType.Builtin builtIn && builtIn.javaType().equals(java.util.UUID.class)
                        && !expression.optional() && expression.type() instanceof ResolvedType.Declared declared
                        && declared.symbol().kind() == TypeSymbol.Kind.ID) {
                    value = CodeBlock.of("$L.asString()", expression.code());
                } else {
                    requireType(input.value, expression, type, false);
                    value = CodeBlock.of("$T.valueOf($L)", String.class, expression.code());
                }
                if (values.putIfAbsent(binding.externalName(), value) != null)
                    throw error(location(source, input.name), "Duplicate MVC input '" + name + "'");
            }
            return values;
        }

        private CodeBlock uri(Route route, Map<String, CodeBlock> values, ParserRuleContext at) {
            var builder = ClassName.get("org.springframework.web.util", "UriComponentsBuilder");
            var path = CodeBlock.builder().add("$T.fromPath($S).encode().buildAndExpand($T.ofEntries(", builder, route.template(), Map.class);
            var entries = new ArrayList<CodeBlock>();
            for (var binding : route.bindings()) {
                if (!binding.kind().equals("path")) continue;
                var value = values.get(binding.externalName());
                if (value == null)
                    throw error(location(source, at), "Missing route path variable '" + binding.name() + "'");
                entries.add(CodeBlock.of("$T.entry($S, $L)", Map.class, binding.externalName(), value));
            }
            return path.add("$L)).toUriString()", CodeBlock.join(entries, ", ")).build();
        }

        private void request(MethodSpec.Builder method, Map<String, Expression> fixtures, TestStepContext step, int response) {
            if (!profile.equals("mvc"))
                throw error(location(source, step.endpoint), "Sending MVC requests requires the mvc profile");
            var route = route(step.endpoint);
            var values = requestBindings(route, step.testBinding(), fixtures, false);
            var builders = ClassName.get("org.springframework.test.web.servlet.request", "MockMvcRequestBuilders");
            var request = CodeBlock.builder().add("$T.request($T.valueOf($S), $T.create($L))", builders,
                    ClassName.get("org.springframework.http", "HttpMethod"), route.verb(), java.net.URI.class, uri(route, values, step.endpoint));
            for (var binding : route.bindings()) {
                var value = values.get(binding.externalName());
                if (value != null && !binding.kind().equals("path")) {
                    String methodName = switch (binding.kind()) {
                        case "form" -> "formField";
                        case "query" -> "queryParam";
                        case "header" -> "header";
                        default -> throw error(location(source, step.endpoint), "Unsupported MVC input kind '" + binding.kind() + "'");
                    };
                    request.add(".$L($S, $L)", methodName, binding.externalName(), value);
                }
            }
            if (step.csrf != null) request.add(".with($T.csrf())",
                    ClassName.get("org.springframework.security.test.web.servlet.request", "SecurityMockMvcRequestPostProcessors"));
            method.addStatement("var response$L = spring.getBean($T.class).perform($L).andReturn().getResponse()",
                    response, ClassName.get("org.springframework.test.web.servlet", "MockMvc"), request.build());
        }

        private void assertResponse(MethodSpec.Builder method, Map<String, Expression> fixtures,
                                    TestAssertionContext assertion, int response) {
            if (response == 0) throw error(location(source, assertion), "Expected a preceding MVC request");
            if (assertion.responseStatus != null) {
                int status;
                try { status = Integer.parseInt(assertion.responseStatus.getText()); }
                catch (NumberFormatException failure) { throw error(location(source, assertion), "Invalid HTTP status"); }
                if (status < 100 || status > 599) throw error(location(source, assertion), "Invalid HTTP status");
                method.addStatement("$T.assertEquals($L, response$L.getStatus(), statement)", ASSERTIONS, status, response);
            } else if (assertion.responseText != null) {
                String token = assertion.responseText.getText();
                String expected;
                try { expected = token.substring(1, token.length() - 1).translateEscapes(); }
                catch (IllegalArgumentException failure) { throw error(location(source, assertion), "Invalid response text literal"); }
                method.addStatement("$T.assertTrue(response$L.getContentAsString($T.UTF_8).contains($S), statement)",
                        ASSERTIONS, response, java.nio.charset.StandardCharsets.class, expected);
            } else {
                var route = route(assertion.actionEndpoint);
                var values = requestBindings(route, assertion.testBinding(), fixtures, true);
                var path = uri(route, values, assertion.actionEndpoint);
                var jsoup = ClassName.get("org.jsoup", "Jsoup");
                method.addStatement("$T.$L($T.parse(response$L.getContentAsString($T.UTF_8)).select($S).stream().anyMatch(form -> $L.equals(form.attr($S)) && $S.equalsIgnoreCase(form.attr($S))), statement)",
                        ASSERTIONS, assertion.actionPresence.getText().equals("present") ? "assertTrue" : "assertFalse",
                        jsoup, response, java.nio.charset.StandardCharsets.class, "form[action]", path, "action", route.verb(), "method");
            }
        }

        private void invoke(MethodSpec.Builder method, Map<String, Expression> fixtures, TestStepContext step) {
            if (configuration == null)
                throw error(location(source, step.operation), "Invoking a usecase requires a suite test configuration");
            var type = resolve(step.operation.getText(), step.operation);
            if (!(definition(type) instanceof UseCaseNode useCase))
                throw error(location(source, step.operation), "Expected a usecase declaration");
            reference(step.operation, type.symbol().location());
            var args = arguments(step, step.testArguments(), useCase.parameters(), fixtures);
            var invocation = CodeBlock.of("spring.getBean($L.class).execute($L)", JavaTypeNames.canonicalName(type), args);
            if (step.authorizationFailure != null) {
                if (step.result != null)
                    throw error(location(source, step.result), "An expected authorization failure has no result");
                if (step.authorizationFailure.getText().equals("invalid")) {
                    method.addStatement("$T.assertThrows($T.class, () -> $L, statement)",
                            ASSERTIONS, org.vernac.runtime.DomainValidationException.class, invocation);
                    return;
                }
                AuthorizationException.Code code;
                try {
                    code = AuthorizationException.Code.valueOf(step.authorizationFailure.getText());
                } catch (IllegalArgumentException invalid) {
                    throw error(location(source, step.authorizationFailure), "Unknown authorization failure code");
                }
                method.addStatement("$T.assertEquals($T.$L, $T.assertThrows($T.class, () -> $L, statement).code(), statement)",
                        ASSERTIONS, AuthorizationException.Code.class, code.name(), ASSERTIONS,
                        AuthorizationException.class, invocation);
            } else if (step.result == null) {
                method.addStatement("$L", invocation);
            } else if (!useCase.resultFields().isEmpty()) {
                bind(method, fixtures, step.result, new Expression(invocation, type));
            } else {
                var result = useCase.resultType().orElseThrow(() ->
                        error(location(source, step.result), "Usecase does not return a value"));
                bind(method, fixtures, step.result, new Expression(invocation, main.typeOf(result), result.isOptional()));
            }
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

        private Expression predicateExpression(KeywordExpression expression, Map<String, Expression> fixtures) {
            return switch (expression) {
                case KeywordExpression.Name name -> {
                    var fixture = fixtures.get(name.name());
                    if (fixture == null) throw error(name.location(), "Unknown fixture '" + name.name() + "'");
                    yield fixture;
                }
                case KeywordExpression.Literal literal -> predicateLiteral(literal);
                case KeywordExpression.UnaryMessage message -> predicateRead(message, fixtures);
                case KeywordExpression.Prefix prefix -> {
                    var operand = predicateExpression(prefix.operand(), fixtures);
                    requireScalar(prefix.operand(), operand, prefix.operator().equals("!") ? boolean.class : int.class);
                    yield new Expression(CodeBlock.of("($L($L))", prefix.operator(), operand.code()), operand.type());
                }
                case KeywordExpression.Binary binary -> predicateBinary(binary, fixtures);
                case KeywordExpression.Match match -> predicateMatch(match, fixtures);
                case KeywordExpression.Message message -> keywordCall(message, fixtures, MethodNode.Mode.READ);
            };
        }

        private void keywordAction(MethodSpec.Builder method, Map<String, Expression> fixtures,
                                   TestStepContext step) {
            var action = KeywordExpressionParser.build(source, step.keywordAction);
            if (!(action instanceof KeywordExpression.Message message))
                throw error(action.location(), "Expected a keyword message in the action");
            var invocation = keywordCall(message, fixtures, MethodNode.Mode.MODIFY);
            if (step.expectedRejection != null) {
                if (step.result != null)
                    throw error(location(source, step.result), "A rejected action cannot bind a result");
                method.addStatement("$T.assertEquals($S, $T.assertThrows($T.class, () -> $L, statement).code(), statement)",
                        ASSERTIONS, step.expectedRejection.getText(), ASSERTIONS,
                        org.vernac.runtime.NativeValidation.Rejection.class, invocation.code());
            } else if (step.result == null) {
                method.addStatement("$L", invocation.code());
            } else {
                if (invocation.type() instanceof ResolvedType.VoidReturn)
                    throw error(location(source, step.result), "The action does not return a value");
                bind(method, fixtures, step.result, invocation);
            }
        }

        private Expression keywordCall(KeywordExpression.Message message, Map<String, Expression> fixtures,
                                       MethodNode.Mode allowedMode) {
            var receiver = predicateExpression(message.receiver(), fixtures);
            String name = message.parts().getFirst().name();
            var candidates = receiver.optional() ? List.<MethodNode>of() : methods(definition(receiver.type()));
            var matches = candidates.stream()
                    .filter(candidate -> candidate.accessModifier().equals("public") && candidate.name().equals(name))
                    .filter(candidate -> candidate.mode() == MethodNode.Mode.READ || candidate.mode() == allowedMode)
                    .filter(candidate -> candidate.parameters().size() == message.parts().size())
                    .filter(candidate -> java.util.stream.IntStream.range(1, message.parts().size()).allMatch(
                            index -> message.parts().get(index).name().equals(candidate.parameters().get(index).name())))
                    .toList();
            if (matches.size() != 1)
                throw error(message.location(), "Expected one accessible behavior for selector '" + message.selector() + "'");
            var target = matches.getFirst();
            var arguments = new ArrayList<CodeBlock>();
            for (int index = 0; index < message.parts().size(); index++) {
                var part = message.parts().get(index);
                var parameter = target.parameters().get(index);
                var argument = predicateExpression(part.argument(), fixtures);
                var expected = main.typeOf(parameter.type());
                if (!argument.type().equals(expected) || argument.optional())
                    throw error(part.argument().location(), "Expected " + describe(expected, false)
                            + ", got " + describe(argument.type(), argument.optional()));
                references.add(new Reference(part.location(), part.name().codePointCount(0, part.name().length()),
                        index == 0 ? target.location() : parameter.location()));
                arguments.add(argument.code());
            }
            var type = main.typeOf(target.returnType());
            if (type instanceof ResolvedType.VoidReturn && allowedMode == MethodNode.Mode.READ)
                throw error(message.location(), "A predicate message must return a value");
            return new Expression(CodeBlock.of("$L.$L($L)", receiver.code(), name, CodeBlock.join(arguments, ", ")),
                    type, target.returnType().isOptional());
        }

        private Expression predicateMatch(KeywordExpression.Match match, Map<String, Expression> fixtures) {
            var subject = predicateExpression(match.subject(), fixtures);
            if (!subject.optional()) throw error(match.subject().location(), "Match requires an optional value");
            String binding = match.binding().name();
            if (!VernacNames.isIdentifier(binding) || fixtures.containsKey(binding))
                throw error(match.binding().location(), "Invalid or duplicate match binding '" + binding + "'");

            var branchFixtures = new HashMap<>(fixtures);
            String parameter = fixtureName(binding);
            branchFixtures.put(binding, new Expression(CodeBlock.of("$L", parameter), subject.type()));
            var present = predicateExpression(match.onPresent(), branchFixtures);
            var absent = predicateExpression(match.onAbsent(), fixtures);
            if (!present.type().equals(absent.type()) || present.optional() != absent.optional())
                throw error(match.onAbsent().location(), "Expected " + describe(present.type(), present.optional())
                        + ", got " + describe(absent.type(), absent.optional()));
            return new Expression(CodeBlock.of("$L.map($L -> $L).orElseGet(() -> $L)",
                    subject.code(), parameter, present.code(), absent.code()), present.type(), present.optional());
        }

        private Expression predicateRead(KeywordExpression.UnaryMessage message, Map<String, Expression> fixtures) {
            String member = message.selector();
            if (message.receiver() instanceof KeywordExpression.Name name && !fixtures.containsKey(name.name())) {
                var lookup = scope.resolve(name.name(), name.location());
                if (lookup.type().orElse(null) instanceof ResolvedType.Declared type
                        && definition(type) instanceof ValueObjectNode enumeration && enumeration.isEnum()) {
                    var constant = enumeration.enumConstants().stream()
                            .filter(candidate -> candidate.name().equals(member)).findFirst()
                            .orElseThrow(() -> error(message.location(), "Unknown enum member '" + member + "'"));
                    references.add(new Reference(message.location(), member.codePointCount(0, member.length()),
                            constant.location()));
                    return new Expression(CodeBlock.of("$L.$L", JavaTypeNames.canonicalName(type), member), type);
                }
            }
            var receiver = predicateExpression(message.receiver(), fixtures);
            var field = receiver.optional() ? null : fieldType(definition(receiver.type()), member);
            if (field == null) throw error(message.location(), "Unknown readable field '" + member + "'");
            references.add(new Reference(message.location(), member.codePointCount(0, member.length()), field.location()));
            return new Expression(CodeBlock.of("$L.$L()", receiver.code(), member),
                    main.typeOf(field), field.isOptional());
        }

        private Expression predicateBinary(KeywordExpression.Binary binary, Map<String, Expression> fixtures) {
            var left = predicateExpression(binary.left(), fixtures);
            var right = predicateExpression(binary.right(), fixtures);
            String operator = binary.operator();
            var bool = new ResolvedType.Builtin(boolean.class);
            if (operator.equals("==") || operator.equals("!=")) {
                if (!left.type().equals(right.type()) || left.optional() != right.optional())
                    throw error(binary.right().location(), "Expected " + describe(left.type(), left.optional())
                            + ", got " + describe(right.type(), right.optional()));
                return new Expression(CodeBlock.of("($L$T.equals($L, $L))", operator.equals("!=") ? "!" : "",
                        java.util.Objects.class, left.code(), right.code()), bool);
            }
            if (operator.equals("&&") || operator.equals("||")) {
                requireScalar(binary.left(), left, boolean.class);
                requireScalar(binary.right(), right, boolean.class);
                return new Expression(CodeBlock.of("($L $L $L)", left.code(), operator, right.code()), bool);
            }
            requireScalar(binary.left(), left, int.class);
            requireScalar(binary.right(), right, int.class);
            var resultType = Set.of("<", "<=", ">", ">=").contains(operator) ? bool : left.type();
            return new Expression(CodeBlock.of("($L $L $L)", left.code(), operator, right.code()), resultType);
        }

        private Expression predicateLiteral(KeywordExpression.Literal literal) {
            String spelling = literal.spelling();
            try {
                return switch (literal.kind()) {
                    case STRING -> new Expression(CodeBlock.of("$S",
                            spelling.substring(1, spelling.length() - 1).translateEscapes()),
                            new ResolvedType.Builtin(String.class));
                    case INTEGER -> new Expression(CodeBlock.of("$L", Integer.parseInt(spelling)),
                            new ResolvedType.Builtin(int.class));
                    case BOOLEAN -> new Expression(CodeBlock.of("$L", spelling), new ResolvedType.Builtin(boolean.class));
                    case DECIMAL -> throw error(literal.location(), "Decimal literals are not supported in scenario predicates yet");
                };
            } catch (IllegalArgumentException failure) {
                throw error(literal.location(), "Invalid test literal '" + spelling + "'");
            }
        }

        private void requireScalar(KeywordExpression at, Expression actual, Class<?> javaType) {
            var expected = new ResolvedType.Builtin(javaType);
            if (!actual.type().equals(expected) || actual.optional())
                throw error(at.location(), "Expected " + describe(expected, false)
                        + ", got " + describe(actual.type(), actual.optional()));
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
            case UseCaseNode useCase -> useCase.resultFields();
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
