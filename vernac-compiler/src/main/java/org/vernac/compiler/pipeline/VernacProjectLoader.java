// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.language.VernacNames;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Discovers and parses all source files before building the declaration index. */
public final class VernacProjectLoader {
    public VernacProject load(Path sourceRoot) throws IOException {
        return load(sourceRoot, Map.of());
    }

    /** Open editor snapshots override disk contents; unsaved new files are included. */
    public VernacProject load(Path sourceRoot, Map<Path, String> snapshots) throws IOException {
        Path root = sourceRoot.toAbsolutePath().normalize();
        Map<Path, String> overlays = new TreeMap<>();
        for (var entry : snapshots.entrySet()) {
            Path path = entry.getKey().toAbsolutePath().normalize();
            if (!path.startsWith(root) || !path.getFileName().toString().endsWith(".vernac")) {
                throw new IllegalArgumentException("Snapshot is not a Vernac source below " + root + ": " + path);
            }
            overlays.put(path, entry.getValue());
        }
        if (!Files.isDirectory(root) && (Files.exists(root) || overlays.isEmpty())) {
            throw new IOException("Vernac source root is not a directory: " + root);
        }
        Set<Path> paths = new TreeSet<>(overlays.keySet());
        if (Files.isDirectory(root)) {
            try (var files = Files.walk(root)) {
                files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".vernac"))
                        .forEach(paths::add);
            }
        }
        List<VernacSourceFile> sources = new ArrayList<>();
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        var parser = new VernacSourceParser();
        for (Path path : paths) {
            try {
                var unit = parser.parse(path.toString(), overlays.containsKey(path) ? overlays.get(path) : Files.readString(path));
                sources.add(new VernacSourceFile(path, unit));
                validateNamespace(root, path, unit, diagnostics);
            } catch (SemanticValidationException e) {
                diagnostics.addAll(e.diagnostics());
            }
        }
        if (!diagnostics.isEmpty()) throw new SemanticValidationException(diagnostics);
        return index(root, sources);
    }

    /** Indexes parsed snapshots; filesystem layout is checked by load, not by this method. */
    public VernacProject index(Path sourceRoot, List<VernacSourceFile> sources) {
        Path root = sourceRoot.toAbsolutePath().normalize();
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        List<TypeSymbol> symbols = new ArrayList<>();
        for (var source : sources) {
            if (!VernacNames.isNamespace(source.unit().namespace())) {
                diagnostics.add(CompilerDiagnostic.error(source.unit().location(),
                        "Invalid namespace '" + source.unit().namespace() + "'."));
                continue;
            }
            for (var definition : source.unit().definitions()) {
                var declaration = describe(definition);
                addSymbol(source, declaration.name(), declaration.kind(), definition.location(), symbols, diagnostics);
                if (definition instanceof ValueObjectNode vo && vo.collection().isPresent()) {
                    var collection = vo.collection().get();
                    addSymbol(source, collection.customName().orElse(vo.name() + "s"),
                            TypeSymbol.Kind.COLLECTION, collection.location(), symbols, diagnostics);
                } else if (definition instanceof EntityNode entity && entity.collection().isPresent()) {
                    var collection = entity.collection().get();
                    addSymbol(source, collection.customName().orElse(entity.name() + "s"),
                            TypeSymbol.Kind.COLLECTION, collection.location(), symbols, diagnostics);
                }
            }
        }
        var index = new ProjectSymbolIndex(symbols);
        for (var problem : index.problems()) {
            String origins = problem.declarations().stream().map(symbol -> symbol.location().toString())
                    .collect(Collectors.joining(", "));
            for (var symbol : problem.declarations()) {
                diagnostics.add(CompilerDiagnostic.error(symbol.location(),
                        problem.message() + " Declaration locations: " + origins));
            }
        }
        if (!diagnostics.isEmpty()) throw new SemanticValidationException(diagnostics);
        return new VernacProject(root, sources, index);
    }

    private void validateNamespace(Path root, Path file, CompilationUnitNode unit,
                                   List<CompilerDiagnostic> diagnostics) {
        if (!VernacNames.isNamespace(unit.namespace())) {
            diagnostics.add(CompilerDiagnostic.error(unit.location(),
                    "Invalid namespace '" + unit.namespace() + "': expected a Java-compatible qualified name."));
            return;
        }
        Path expectedDirectory = root.resolve(unit.namespace().replace('.', '/'));
        if (!file.getParent().equals(expectedDirectory)) {
            diagnostics.add(CompilerDiagnostic.error(unit.location(),
                    "Namespace '" + unit.namespace() + "' requires directory '"
                            + root.relativize(expectedDirectory) + "' relative to the source root, but file is in '"
                            + root.relativize(file.getParent()) + "'. Move the file or correct its namespace."));
        }
    }

    private void addSymbol(VernacSourceFile source, String name, TypeSymbol.Kind kind,
                           SourceLocation location, List<TypeSymbol> symbols,
                           List<CompilerDiagnostic> diagnostics) {
        try {
            var identity = new TypeIdentity(source.unit().namespace(), name);
            symbols.add(new TypeSymbol(identity, kind, source.path(), location));
        } catch (IllegalArgumentException e) {
            diagnostics.add(CompilerDiagnostic.error(location, e.getMessage()));
        }
    }

    private record Declaration(String name, TypeSymbol.Kind kind) { }

    private Declaration describe(TopLevelDefinition definition) {
        return switch (definition) {
            case IdDeclarationNode node -> new Declaration(node.name(), TypeSymbol.Kind.ID);
            case ValueObjectNode node -> new Declaration(node.name(), node.isEnum()
                    ? TypeSymbol.Kind.ENUM : TypeSymbol.Kind.VALUE_OBJECT);
            case EntityNode node -> new Declaration(node.name(), TypeSymbol.Kind.ENTITY);
            case AggregateNode node -> new Declaration(node.name(), TypeSymbol.Kind.AGGREGATE);
            case EventNode node -> new Declaration(node.name(), TypeSymbol.Kind.EVENT);
            case RepositoryNode node -> new Declaration(node.name(), TypeSymbol.Kind.REPOSITORY);
            case PortNode node -> new Declaration(node.name(), TypeSymbol.Kind.PORT);
            case UseCaseNode node -> new Declaration(node.name(), TypeSymbol.Kind.USE_CASE);
            case DomainServiceNode node -> new Declaration(node.name(), TypeSymbol.Kind.DOMAIN_SERVICE);
            case ListenerNode node -> new Declaration(node.listenerName(), TypeSymbol.Kind.LISTENER);
        };
    }
}
