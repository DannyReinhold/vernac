// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.SourceLocation;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectSymbolIndexTest {
    private TypeSymbol symbol(String namespace, String name, String file) {
        return new TypeSymbol(new TypeIdentity(namespace, name), TypeSymbol.Kind.VALUE_OBJECT,
                Path.of(file), new SourceLocation(3, 7));
    }

    @Test
    void findsTypesAcrossFilesWithoutMakingFilenamesPartOfTheirIdentity() {
        var title = symbol("org.example.tasks", "Title", "org/example/tasks/title.vernac");
        var task = symbol("org.example.tasks", "Task", "org/example/tasks/model.vernac");
        var index = new ProjectSymbolIndex(List.of(title, task));
        assertEquals(title, index.find("org.example.tasks.Title").orElseThrow());
        assertEquals(task, index.find("org.example.tasks.Task").orElseThrow());
        assertTrue(index.problems().isEmpty());
    }

    @Test
    void permitsTheSameSimpleNameInDifferentNamespaces() {
        var left = symbol("org.example.sales", "Title", "sales.vernac");
        var right = symbol("org.example.support", "Title", "support.vernac");
        var index = new ProjectSymbolIndex(List.of(left, right));
        assertEquals(left, index.find("org.example.sales.Title").orElseThrow());
        assertEquals(right, index.find("org.example.support.Title").orElseThrow());
        assertTrue(index.problems().isEmpty());
    }

    @Test
    void reportsBothDuplicateOriginsWithoutArbitrarilyResolvingOne() {
        var first = symbol("org.example", "Title", "a.vernac");
        var second = symbol("org.example", "Title", "b.vernac");
        var index = new ProjectSymbolIndex(List.of(second, first));
        var problem = index.problems().getFirst();
        assertEquals(ProjectSymbolIndex.ProblemKind.DUPLICATE_DECLARATION, problem.kind());
        assertEquals(List.of(first, second), problem.declarations());
        assertTrue(index.find("org.example.Title").isEmpty());
    }

    @Test
    void rejectsBuiltinNamesForEveryDeclarationKindAndNamespace() {
        for (var kind : TypeSymbol.Kind.values()) {
            for (String name : List.of("String", "Integer", "Currency", "UUID", "OffsetTime")) {
                var symbol = new TypeSymbol(new TypeIdentity("org.example.custom", name), kind,
                        Path.of("model.vernac"), new SourceLocation(2, 1));
                var index = new ProjectSymbolIndex(List.of(symbol));
                assertEquals(ProjectSymbolIndex.ProblemKind.RESERVED_NAME, index.problems().getFirst().kind());
                assertEquals(List.of(symbol), index.problems().getFirst().declarations());
                assertTrue(index.find(symbol.identity().qualifiedName()).isEmpty());
            }
        }
    }

    @Test
    void reservationIsCaseSensitiveAndLimitedToTheCatalog() {
        var lowercase = symbol("org.example", "currency", "a.vernac");
        var unrelatedJdkName = symbol("org.example", "Thread", "b.vernac");
        var index = new ProjectSymbolIndex(List.of(lowercase, unrelatedJdkName));
        assertTrue(index.problems().isEmpty());
        assertEquals(2, index.inNamespace("org.example").size());
    }

    @Test
    void namespaceEnumerationIsNotRecursiveAndDoesNotUseJavaPackages() {
        var direct = symbol("org.example", "Title", "a.vernac");
        var nested = symbol("org.example.child", "Name", "b.vernac");
        var index = new ProjectSymbolIndex(List.of(direct, nested));
        assertEquals(List.of(direct), index.inNamespace("org.example"));
        assertTrue(index.find("org.example.domain.Title").isEmpty());
        assertTrue(index.find("Title").isEmpty());
        assertTrue(index.find("java.lang.String").isEmpty());
    }

    @Test
    void resultDoesNotDependOnFileDiscoveryOrder() {
        var symbols = List.of(symbol("org.example", "Title", "b.vernac"),
                symbol("org.example", "Title", "a.vernac"),
                symbol("org.example", "Name", "c.vernac"));
        var forward = new ProjectSymbolIndex(symbols);
        var reverse = new ProjectSymbolIndex(symbols.reversed());
        assertEquals(forward.problems(), reverse.problems());
        assertEquals(forward.inNamespace("org.example"), reverse.inNamespace("org.example"));
    }

    @Test
    void indexIsAnImmutableSnapshot() {
        var declarations = new ArrayList<>(List.of(symbol("org.example", "Title", "a.vernac")));
        var index = new ProjectSymbolIndex(declarations);
        declarations.clear();
        assertEquals(1, index.inNamespace("org.example").size());
        assertThrows(UnsupportedOperationException.class, () -> index.inNamespace("org.example").clear());
        assertThrows(UnsupportedOperationException.class, () -> index.problems().clear());
    }

    @Test
    void vernacKeywordNamespaceSegmentsAreAllowedButJavaKeywordsAreNot() {
        assertEquals("org.example.custom.Title",
                new TypeIdentity("org.example.custom", "Title").qualifiedName());
        assertThrows(IllegalArgumentException.class, () -> new TypeIdentity("org.example.class", "Title"));
        assertThrows(IllegalArgumentException.class, () -> new TypeIdentity("org..example", "Title"));
        assertThrows(IllegalArgumentException.class, () -> new TypeIdentity("", "Title"));
        assertThrows(IllegalArgumentException.class, () -> new TypeIdentity("org.example", "record"));
    }
}
