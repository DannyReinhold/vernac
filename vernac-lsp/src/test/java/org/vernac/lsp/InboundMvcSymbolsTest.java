// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

class InboundMvcSymbolsTest {
    @Test
    void formSourcesUsecaseArgumentsAndRedisplayTargetsCompleteAndNavigate() {
        Path root = Path.of("mvc-workspace").toAbsolutePath(),
                path = root.resolve("demo/model.vernac");
        String text =
                "namespace demo; value Text(String); usecase Read() returns Text behavior {execute"
                    + " {return Text.of(\"page\");}} usecase Write(Text title) behavior {execute"
                    + " {}} adapter Web inbound mvc uses Read reader, Write writer {base"
                    + " \"/\";error view \"problem\"; board {GET \"/\";execute reader {} render"
                    + " \"board\" model page;} save {POST \"/\";form String title;execute writer"
                    + " {title <- form.title;}redirect \"/\";errors {when validation then redisplay"
                    + " board status 422;}}}";
        var symbols = new VernacProjectSymbols(root, Map.of(path, text));
        assertTrue(
                symbols.complete(path, new Position(0, text.indexOf("form.title") + 2)).stream()
                        .anyMatch(c -> c.getLabel().equals("form")));
        assertTrue(
                symbols.complete(path, new Position(0, text.indexOf("form.title") + 7)).stream()
                        .anyMatch(c -> c.getLabel().equals("form.title")));
        var locations = symbols.definition(path, new Position(0, text.indexOf("form.title") + 7));
        assertEquals(
                text.indexOf("form String title") + 12,
                locations.getFirst().getRange().getStart().getCharacter());
        assertTrue(
                symbols
                        .complete(path, new Position(0, text.indexOf("redisplay board") + 12))
                        .stream()
                        .anyMatch(c -> c.getLabel().equals("board")));
        var target =
                symbols.definition(path, new Position(0, text.indexOf("redisplay board") + 12));
        assertEquals(
                text.indexOf("board {GET"), target.getFirst().getRange().getStart().getCharacter());
        assertTrue(
                symbols.hover(path, new Position(0, text.indexOf("redisplay board") + 12))
                        .contains("MVC page loader"));
        assertTrue(
                symbols.complete(path, new Position(0, text.indexOf("title <-") + 2)).stream()
                        .anyMatch(c -> c.getLabel().equals("title")));
    }

    @Test
    void declarativeViewReferencesCompleteAtTheEndOfTheTypedName() {
        Path root = Path.of("mvc-workspace").toAbsolutePath();
        Path view = root.resolve("demo/view.vernac");
        Path routes = root.resolve("demo/routes.vernac");
        String viewText = "namespace demo; view Rooms() { page \"Rooms\" { heading \"Rooms\"; } }";
        String routeText = "namespace demo; adapter Web inbound mvc { index { GET \"/\"; render Roo; } }";
        var symbols = new VernacProjectSymbols(root, Map.of(view, viewText, routes, routeText));
        int reference = routeText.indexOf("Roo");
        assertTrue(symbols.complete(routes, new Position(0, reference + 3)).stream()
                .anyMatch(item -> item.getLabel().equals("Rooms")));

        var exact = new VernacProjectSymbols(root, Map.of(view, viewText, routes, routeText.replace("Roo;", "Rooms;")));
        var target = exact.definition(routes, new Position(0, reference)).getFirst();
        assertEquals(view.toUri().toString(), target.getUri());
        assertEquals(viewText.indexOf("Rooms()"), target.getRange().getStart().getCharacter());
    }
}
