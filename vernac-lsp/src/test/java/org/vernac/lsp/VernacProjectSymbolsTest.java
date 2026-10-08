// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VernacProjectSymbolsTest {
    final Path root = Path.of("test workspace/src/main/vernac").toAbsolutePath();
    final Map<Path, String> files = new LinkedHashMap<>();
    Path file(String namespace, String name, String body) {
        Path path = root.resolve(namespace.replace('.', '/')).resolve(name + ".vernac");
        files.put(path, "namespace " + namespace + ";\n" + body);
        return path;
    }
    Position at(Path path, String marker) {
        String text = files.get(path);
        int offset = text.indexOf(marker) + marker.length();
        String prefix = text.substring(0, offset);
        return new Position((int) prefix.chars().filter(c -> c == '\n').count(),
                offset - prefix.lastIndexOf('\n') - 1);
    }
    List<CompletionItem> complete(Path path, String marker) {
        return new VernacProjectSymbols(root, files).complete(path, at(path, marker));
    }
    List<String> labels(Path path, String marker) {
        return complete(path, marker).stream().map(CompletionItem::getLabel).toList();
    }
    List<Location> definition(Path path, String beforeLastCharacter) {
        return new VernacProjectSymbols(root, files).definition(path, at(path, beforeLastCharacter));
    }

    @Test void collectionCompletionAndNavigationWorkAcrossNamespaces() {
        var ids = file("model", "ids", "id TaskId list;");
        var states = file("model", "states", "value State = OPEN | DONE set States;");
        file("model", "entities", "entity Task[TaskId](String title) list Tasks;");
        var current = file("view", "view", "import model.*; value View(");
        var available = labels(current, "View(");
        assertTrue(available.containsAll(List.of("TaskIds", "States")));
        assertFalse(available.contains("Tasks"));
        files.put(current, "namespace view; import model.TaskIds; value View(TaskIds);");
        assertEquals(ids.toUri().toString(), definition(current, "View(TaskId").getFirst().getUri());
        files.put(current, "namespace view; value View(model.States);");
        assertEquals(states.toUri().toString(), definition(current, "View(model.State").getFirst().getUri());
    }

    @Test void enumTypeCompletionAndNavigationDoNotExposeConstantsAsTypes() {
        var target = file("states", "status", "value Status = MeinTyp | String | name | values;");
        var current = file("tasks", "draft", "import states.Status; value Draft(Sta");
        assertEquals(List.of("Status"), labels(current, "Draft(Sta"));
        files.put(current, "namespace tasks; import states.Status; value Draft(Status);");
        assertEquals(target.toUri().toString(), definition(current, "Draft(Sta").getFirst().getUri());
        files.put(current, "namespace tasks; import states.*; value Draft(");
        assertFalse(labels(current, "Draft(").contains("MeinTyp"));
        assertFalse(labels(current, "Draft(").contains("name"));
        assertEquals(1, labels(current, "Draft(").stream().filter("String"::equals).count());
    }

    @Test void completesUnfinishedValueUsingSiblingDeclarationsAndBuiltins() {
        file("tasks", "types", "value Title(String value); id TaskId; value Status = OPEN | DONE;");
        var current = file("tasks", "draft", "value Draft(Ti");
        assertEquals(List.of("Title"), labels(current, "Draft(Ti"));
        files.put(current, "namespace tasks;\nvalue Draft(");
        var labels = labels(current, "Draft(");
        assertTrue(labels.containsAll(List.of("Title", "TaskId", "Status", "int", "Currency", "LocalDate")));
        assertFalse(labels.contains("List"));
        assertFalse(labels.contains("Set"));
    }

    @Test void explicitAndWildcardImportsAreFileLocal() {
        file("people", "names", "value Name(String value);");
        file("tasks", "imports", "import people.Name; value Other(Name value);");
        var current = file("tasks", "draft", "value Draft(Na");
        assertEquals(List.of(), labels(current, "Draft(Na"));
        files.put(current, "namespace tasks; import people.*; value Draft(Na");
        assertEquals(List.of("Name"), labels(current, "Draft(Na"));
        files.put(current, "namespace tasks; import people.Name; value Draft(Name value);");
        assertEquals(1, definition(current, "Draft(Nam").size());
    }

    @Test void ambiguousImportsHaveNoArbitraryWinnerAndExplicitImportDisambiguates() {
        file("a", "names", "value Name(String value);");
        file("b", "names", "value Name(String value);");
        var current = file("tasks", "draft", "import a.*; import b.*; value Draft(Name value);");
        assertEquals(List.of(), labels(current, "Draft(Na"));
        assertEquals(List.of(), definition(current, "Draft(Nam"));
        files.put(current, "namespace tasks; import a.*; import b.*; import b.Name; value Draft(Name value);");
        assertEquals(List.of("Name"), labels(current, "Draft(Na"));
        assertTrue(definition(current, "Draft(Nam").getFirst().getUri().endsWith("b/names.vernac"));
    }

    @Test void localTypesWinOverWildcardImportsAndDuplicatesAreUnavailable() {
        file("people", "name", "value Name(String value);");
        var local = file("tasks", "name", "value Name(String value);");
        var current = file("tasks", "draft", "import people.*; value Draft(Name value);");
        assertEquals(local.toUri().toString(), definition(current, "Draft(Nam").getFirst().getUri());
        file("tasks", "duplicate", "value Name(String value);");
        // A broken namespace must not navigate to one of the duplicate local declarations.
        assertEquals(List.of(), definition(current, "Draft(Nam"));
    }

    @Test void qualifiedCompletionReplacesTheWholeReference() {
        file("org.custom", "title", "value Title(String value);");
        var current = file("tasks", "draft", "value Draft(org.custom.Tit value);");
        var items = complete(current, "Draft(org.custom.Ti");
        assertEquals(List.of("org.custom.Title"), items.stream().map(CompletionItem::getLabel).toList());
        var edit = items.getFirst().getTextEdit().getLeft();
        assertEquals("org.custom.Title", edit.getNewText());
        assertEquals(new Position(1, 12), edit.getRange().getStart());
        assertEquals(new Position(1, 26), edit.getRange().getEnd());
    }

    @Test void unfinishedImportOffersTypesAndNonRecursiveNamespaceWildcards() {
        file("org.custom", "title", "value Title(String value);");
        file("tasks", "title", "value Local(String value);");
        var current = file("tasks", "draft", "import org.c");
        assertEquals(List.of("org.custom.*", "org.custom.Title"), labels(current, "import org.c"));
        files.put(current, "namespace tasks; import tasks.");
        assertEquals(List.of(), labels(current, "import tasks."));
    }

    @Test void navigationUsesActualDeclarationNameAndUtf16Columns() {
        var target = file("people", "names", "/* 😀 */ value Name(String value);");
        var current = file("tasks", "draft", "import people.Name; value Draft(people.Name name);");
        var expected = new Location(target.toUri().toString(), new Range(new Position(1, 15), new Position(1, 19)));
        assertEquals(List.of(expected), definition(current, "Draft(people.Nam"));
        assertEquals(List.of(expected), definition(current, "import people.Nam"));
    }

    @Test void commentsStringsAndJavaBodiesDoNotDeclareOrReferenceTypes() {
        file("tasks", "title", "value Title(String value);");
        var current = file("tasks", "draft", """
                // value Phantom(String value);
                value Draft(String value) validates { require(!value.isBlank(), "Title"); } {
                    public String example() { return "Title"; }
                }
                """);
        assertEquals(List.of(), definition(current, "\"Titl"));
        assertEquals(List.of(), labels(current, "// value Ph"));
        assertEquals(List.of(), labels(current, "return \"Tit"));
        var other = file("tasks", "other", "value Other(Ph");
        assertEquals(List.of(), labels(other, "Other(Ph"));
    }

    @Test void valueFieldsOfferValueCollectionsButNotEntities() {
        file("people", "title", "value Title(String value);");
        file("tasks", "entities", "id TaskId; entity Title[TaskId](String value); value Label(String value) list Labels;");
        var current = file("tasks", "draft", "import people.*; value Draft(");
        var labels = labels(current, "Draft(");
        assertTrue(labels.containsAll(List.of("TaskId", "Label")));
        assertFalse(labels.contains("Title"));
        assertTrue(labels.contains("Labels"));
    }

    @Test void unicodeCompletionReplacesSupplementaryNamesAndNavigatesPrecisely() {
        var target = file("de.aufträge", "größen", "/* 😀 */ value 𐐀name(String größe);");
        var current = file("例.注文", "model", "import de.aufträge.*; value Auftrag(de.aufträge.𐐀na titel);");
        var completion = complete(current, "Auftrag(de.aufträge.𐐀").getFirst();
        assertEquals("de.aufträge.𐐀name", completion.getLabel());
        var edit = completion.getTextEdit().getLeft();
        assertEquals("de.aufträge.𐐀name", edit.getNewText());
        String line = files.get(current).split("\n")[1];
        assertEquals(line.indexOf("de.aufträge.𐐀na"), edit.getRange().getStart().getCharacter());
        assertEquals(line.indexOf("de.aufträge.𐐀na") + "de.aufträge.𐐀na".length(), edit.getRange().getEnd().getCharacter());
        files.put(current, files.get(current).replace("𐐀na titel", "𐐀name titel"));
        var location = definition(current, "Auftrag(de.aufträge.𐐀na").getFirst();
        assertEquals(target.toUri().toString(), location.getUri());
        assertEquals(new Position(1, "/* 😀 */ value ".length()), location.getRange().getStart());
        assertEquals("𐐀name".length(), location.getRange().getEnd().getCharacter() - location.getRange().getStart().getCharacter());
    }

    @Test void normalizedSpellingsResolveToDifferentDeclarations() {
        var composed = file("tasks", "one", "value Größe(String value);");
        var decomposed = file("tasks", "two", "value Gro\u0308ße(String value);");
        var current = file("tasks", "model", "value Pair(Größe first, Gro\u0308ße second);");
        assertEquals(composed.toUri().toString(), definition(current, "Pair(Grö").getFirst().getUri());
        assertEquals(decomposed.toUri().toString(), definition(current, "first, Gro\u0308").getFirst().getUri());
    }

    @Test void wrongNamespaceAndArbitraryJavaNamesAreNotResolved() {
        var wrong = file("tasks", "wrong", "value Wrong(String value);");
        files.put(wrong, "namespace elsewhere; value Wrong(String value);");
        var current = file("tasks", "draft", "value Draft(java.lang.String value);");
        assertEquals(List.of(), definition(current, "Draft(java.lang.Strin"));
        assertEquals(List.of(), labels(current, "Draft(java.lang.Str"));
    }
}
