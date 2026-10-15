// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.debug;

import com.palantir.javapoet.JavaFile;
import com.sun.source.tree.*;
import com.sun.source.util.*;

import org.vernac.compiler.ast.*;
import org.vernac.compiler.modules.ModuleDescriptor;
import org.vernac.compiler.pipeline.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import javax.tools.*;

/** Build-time line correspondence for copied domain Java bodies. Coordinates are one-based. */
public record BehaviorSourceMap(int formatVersion, List<Entry> entries) {
    public static final String HASH_FIELD = "__vernacDebugHash";
    public static final String FILE = ".vernac-behavior-debug.json";

    public record Entry(
            String javaClass,
            String method,
            String javaFile,
            int javaLine,
            String source,
            int sourceLine,
            String sourceHash,
            String javaHash,
            String runtimeHash) {}

    public BehaviorSourceMap {
        entries = List.copyOf(entries);
    }

    public static BehaviorSourceMap empty() {
        return new BehaviorSourceMap(1, List.of());
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            text.replace("\r\n", "\n")
                                                    .replace('\r', '\n')
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public void write(Path output) throws java.io.IOException {
        Path destination = output.resolve(FILE),
                temporary = Files.createTempFile(output, ".vernac-debug-", ".tmp");
        try {
            ModuleDescriptor.write(temporary, this);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static BehaviorSourceMap read(Path output) throws java.io.IOException {
        var map =
                ModuleDescriptor.JSON.readValue(
                        Files.readString(output.resolve(FILE)), BehaviorSourceMap.class);
        if (map.formatVersion() != 1) throw new java.io.IOException("Unsupported Vernac debug map");
        return map;
    }

    public static BehaviorSourceMap build(ResolvedProject project, List<JavaFile> files) {
        Map<String, JavaFile> generated = new HashMap<>();
        files.forEach(f -> generated.put(f.packageName() + "." + f.typeSpec().name(), f));
        List<Entry> entries = new ArrayList<>();
        for (var source : project.project().sources()) {
            if (source.external() || !Files.isRegularFile(source.path())) continue;
            String original;
            try {
                original = Files.readString(source.path());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read debug source " + source.path(), e);
            }
            String relative =
                    project.project()
                            .sourceRoot()
                            .relativize(source.path())
                            .toString()
                            .replace('\\', '/');
            if (relative.startsWith("../") || Path.of(relative).isAbsolute()) continue;
            ValidationSourceMaps.add(entries, generated, source.unit(), relative, original);
            MappingSourceMaps.add(entries, generated, source.unit(), project, relative, original);
            for (var definition : source.unit().definitions()) {
                List<MethodNode> methods =
                        definition instanceof ValueObjectNode v
                                ? v.methods()
                                : definition instanceof DomainServiceNode service
                                        ? service.methods().stream()
                                                .map(ServiceMethodNode::method)
                                                .toList()
                                        : MutableDomain.of(definition)
                                                .map(MutableDomain::methods)
                                                .orElse(List.of());
                String name =
                        definition instanceof ValueObjectNode v
                                ? v.name()
                                : definition instanceof DomainServiceNode service
                                        ? service.name()
                                        : MutableDomain.of(definition)
                                                .map(MutableDomain::name)
                                                .orElse("");
                add(
                        entries,
                        generated.get(
                                source.unit().namespace() + ".domain.__VernacBehavior_" + name),
                        methods.stream().map(Body::of).toList(),
                        relative,
                        original);
                if (definition instanceof OutboundAdapterNode adapter)
                    add(
                            entries,
                            generated.get(
                                    source.unit().namespace()
                                            + ".adapter.outbound.__VernacBehavior_"
                                            + adapter.name()),
                            adapter.methods().stream().map(m -> Body.of(m.method())).toList(),
                            relative,
                            original);
                if (definition instanceof UseCaseNode u)
                    addOperation(
                            entries,
                            generated,
                            source.unit().namespace() + ".usecase",
                            u,
                            relative,
                            original);
                else if (definition instanceof ListenerNode listener)
                    addOperation(
                            entries,
                            generated,
                            source.unit().namespace() + ".adapter.inbound.event",
                            listener.operation(),
                            relative,
                            original);
                for (var c : CollectionDeclaration.of(definition))
                    add(
                            entries,
                            generated.get(
                                    source.unit().namespace()
                                            + ".domain.__VernacBehavior_"
                                            + c.name()),
                            c.definition().customMethods().stream().map(Body::of).toList(),
                            relative,
                            original);
            }
        }
        return new BehaviorSourceMap(
                1,
                entries.stream()
                        .sorted(
                                Comparator.comparing(Entry::javaClass)
                                        .thenComparingInt(Entry::javaLine))
                        .toList());
    }

    private static void addOperation(
            List<Entry> entries,
            Map<String, JavaFile> generated,
            String packageName,
            UseCaseNode operation,
            String source,
            String original) {
        List<Body> bodies = new ArrayList<>();
        bodies.add(
                new Body(
                        operation.location(),
                        "execute",
                        operation.bodyCode(),
                        operation.implementation().isPresent()));
        bodies.addAll(operation.methods().stream().map(Body::of).toList());
        add(
                entries,
                generated.get(packageName + ".__VernacBehavior_" + operation.name()),
                bodies,
                source,
                original);
    }

    record Body(SourceLocation location, String name, String code, boolean external) {
        static Body of(MethodNode method) {
            return new Body(
                    method.location(),
                    method.name(),
                    method.bodyCode(),
                    method.implementation().isPresent());
        }
    }

    static void add(
            List<Entry> entries,
            JavaFile generated,
            List<Body> methods,
            String source,
            String original) {
        if (generated == null || methods.isEmpty()) return;
        String java = generated.toString(),
                identity = generated.packageName() + "." + generated.typeSpec().name();
        String quoted =
                generated.typeSpec().fieldSpecs().stream()
                        .filter(f -> f.name().equals(HASH_FIELD))
                        .map(f -> f.initializer().toString())
                        .findFirst()
                        .orElse("");
        if (!quoted.matches("\"[0-9a-f]{64}\"")) return;
        String runtimeHash = quoted.substring(1, quoted.length() - 1),
                sourceHash = hash(original),
                javaHash = hash(java);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new IllegalStateException("Behavior debug mapping requires a JDK");
        var input =
                new SimpleJavaFileObject(
                        URI.create("string:///Behavior.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignored) {
                        return java;
                    }
                };
        try (var manager =
                compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    manager,
                                    d -> {},
                                    List.of("-proc:none"),
                                    null,
                                    List.of(input));
            var unit = task.parse().iterator().next();
            var positions = Trees.instance(task).getSourcePositions();
            var type =
                    (ClassTree)
                            unit.getTypeDecls().stream()
                                    .filter(t -> t instanceof ClassTree)
                                    .findFirst()
                                    .orElseThrow();
            var parsed =
                    type.getMembers().stream()
                            .filter(t -> t instanceof MethodTree m && m.getReturnType() != null)
                            .map(t -> (MethodTree) t)
                            .toList();
            if (parsed.size() != methods.size()) return;
            for (int i = 0; i < methods.size(); i++) {
                var method = methods.get(i);
                var target = parsed.get(i);
                if (method.external()
                        || target.getBody() == null
                        || !target.getName().contentEquals(method.name())
                        || method.code().isBlank()) continue;
                int sourceOffset =
                        offset(original, method.location().line(), method.location().column());
                int bodyStart =
                        bodyStart(
                                original,
                                method.code(),
                                sourceOffset,
                                method.name().equals("execute")
                                        && (generated.packageName().endsWith(".usecase")
                                                || generated
                                                        .packageName()
                                                        .endsWith(".adapter.inbound.event")));
                if (bodyStart < 0) continue;
                int start = (int) positions.getStartPosition(unit, target.getBody()) + 1,
                        end = (int) positions.getEndPosition(unit, target.getBody()) - 1;
                if (start < 1 || end < start) continue;
                var sourceLines = lines(method.code(), bodyStart);
                var javaLines = lines(java.substring(start, end), start);
                var a = sourceLines.stream().filter(l -> !l.text().isBlank()).toList();
                var b = javaLines.stream().filter(l -> !l.text().isBlank()).toList();
                if (a.size() != b.size()) continue;
                boolean equal = true;
                for (int j = 0; j < a.size(); j++)
                    if (!a.get(j).text().strip().equals(b.get(j).text().strip())) equal = false;
                if (!equal) continue; // Never guess when the generator changes the body.
                for (int j = 0; j < a.size(); j++)
                    entries.add(
                            new Entry(
                                    identity,
                                    method.name(),
                                    identity.replace('.', '/') + ".java",
                                    line(java, b.get(j).offset()),
                                    source,
                                    line(original, a.get(j).offset()),
                                    sourceHash,
                                    javaHash,
                                    runtimeHash));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot parse generated behavior for debug mapping", e);
        }
    }

    private static int bodyStart(String original, String code, int from, boolean execute) {
        var lexer =
                new org.vernac.compiler.parser.VernacLexer(
                        org.antlr.v4.runtime.CharStreams.fromString(original));
        lexer.removeErrorListeners();
        var parser =
                new org.vernac.compiler.parser.VernacParser(
                        new org.antlr.v4.runtime.CommonTokenStream(lexer));
        parser.removeErrorListeners();
        return bodyStart(parser.compilationUnit(), original, code, from, execute);
    }

    private static int bodyStart(
            org.antlr.v4.runtime.tree.ParseTree node,
            String original,
            String code,
            int from,
            boolean execute) {
        if (node instanceof org.vernac.compiler.parser.VernacParser.RawJavaBlockContext block
                && (!execute
                        || block.getParent()
                                instanceof
                                org.vernac.compiler.parser.VernacParser
                                        .UsecaseBehaviorMemberContext)) {
            int a = original.offsetByCodePoints(0, block.getStart().getStartIndex());
            int b = original.offsetByCodePoints(0, block.getStop().getStopIndex() + 1);
            String inside = original.substring(a, b);
            int leading = inside.length() - inside.stripLeading().length();
            if (a >= from && inside.trim().equals(code)) return a + leading;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            int result = bodyStart(node.getChild(i), original, code, from, execute);
            if (result >= 0) return result;
        }
        return -1;
    }

    private record TextLine(String text, int offset) {}

    private static List<TextLine> lines(String text, int offset) {
        List<TextLine> result = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            result.add(new TextLine(line, offset));
            offset += line.length() + 1;
        }
        return result;
    }

    private static int line(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    private static int offset(String text, int line, int column) {
        int offset = 0;
        for (int i = 1; i < line; i++) {
            int next = text.indexOf('\n', offset);
            if (next < 0) return text.length();
            offset = next + 1;
        }
        int end = text.indexOf('\n', offset);
        if (end < 0) end = text.length();
        return text.offsetByCodePoints(
                offset, Math.min(text.codePointCount(offset, end), Math.max(0, column - 1)));
    }
}
