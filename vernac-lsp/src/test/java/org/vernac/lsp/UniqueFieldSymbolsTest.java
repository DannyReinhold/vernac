// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

class UniqueFieldSymbolsTest {
    final Path root = Path.of("unique-workspace").toAbsolutePath();
    final Path file = root.resolve("model/claim.vernac");
    final String base = "namespace model; id ClaimId; value LoginKey(String); ";

    VernacProjectSymbols symbols(String text) {
        return new VernacProjectSymbols(root, Map.of(file, text));
    }

    Position at(String text, String marker) {
        return new Position(0, text.lastIndexOf(marker) + marker.length());
    }

    @Test
    void omittedFieldNamesPreserveTypeNavigationAndIncompleteTypeCompletion() {
        String text = base + "global aggregate Claim[ClaimId](unique LoginKey);";
        var location = symbols(text).definition(file, at(text, "unique LoginK"));
        assertEquals(1, location.size());
        assertEquals(
                text.indexOf("LoginKey"), location.getFirst().getRange().getStart().getCharacter());
        String partial = base + "global aggregate Claim[ClaimId](unique LoginK);";
        assertTrue(
                symbols(partial).complete(file, at(partial, "unique LoginK")).stream()
                        .anyMatch(x -> x.getLabel().equals("LoginKey")));
    }

    @Test
    void hoverExplainsScopeAndDatabaseEnforcement() {
        for (String scope : List.of("global", "tenant")) {
            String text = base + scope + " aggregate Claim[ClaimId](unique LoginKey loginKey);";
            String hover = symbols(text).hover(file, at(text, "uniqu"));
            assertTrue(hover.contains("loginKey"), hover);
            assertTrue(hover.contains("database unique constraint"), hover);
            assertTrue(
                    hover.contains(
                            scope.equals("tenant") ? "within each tenant" : "across instances"),
                    hover);
            assertEquals(hover, symbols(text).hover(file, at(text, "loginKe")));
        }
    }

    @Test
    void modifierCompletionIsAggregateLocalAndDoesNotRepeatExistingMarkers() {
        String start = base + "global aggregate Claim[ClaimId](uni);";
        assertTrue(
                symbols(start).complete(file, at(start, "(uni")).stream()
                        .anyMatch(
                                item ->
                                        item.getLabel().equals("unique")
                                                && item.getKind()
                                                        == org.eclipse.lsp4j.CompletionItemKind
                                                                .Keyword));
        String marked = base + "global aggregate Claim[ClaimId](unique mu);";
        var choices = symbols(marked).complete(file, at(marked, "unique mu"));
        assertTrue(choices.stream().anyMatch(item -> item.getLabel().equals("mut")));
        assertTrue(choices.stream().noneMatch(item -> item.getLabel().equals("unique")));
        String value = base + "value Other(uni);";
        assertTrue(
                symbols(value).complete(file, at(value, "(uni")).stream()
                        .noneMatch(item -> item.getLabel().equals("unique")));
    }
}
