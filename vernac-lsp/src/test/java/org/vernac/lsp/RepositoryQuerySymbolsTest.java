// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import org.junit.jupiter.api.Test;
import org.eclipse.lsp4j.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryQuerySymbolsTest {
    final Path root=Path.of("query-workspace").toAbsolutePath();
    final Path model=root.resolve("model/tour.vernac");
    final Path repo=root.resolve("api/repo.vernac");
    final String domain="namespace model; id TourId; value Title(String); aggregate Tour[TourId](Title title) list Tours;";
    VernacProjectSymbols symbols(String text) { return new VernacProjectSymbols(root,Map.of(model,domain,repo,text)); }
    Position at(String text,String marker) { return new Position(0,text.indexOf(marker)+marker.length()); }
    @Test void completesFieldsAndParametersAcrossNamespaces() {
        String prefix="namespace api; import model.*; repository TourRepository for Tour { find Tours named(Title title) ";
        String fields=prefix+"where tit; }";
        var choices=symbols(fields).complete(repo,at(fields,"where tit"));
        assertNotNull(choices);
        assertTrue(choices.stream().anyMatch(c->c.getLabel().equals("title")));
        String params=prefix+"where title = :tit; }";
        assertTrue(symbols(params).complete(repo,at(params,":tit")).stream().anyMatch(c->c.getLabel().equals("title")));
    }
    @Test void navigatesFieldAndParameterWithoutConfusingSameNames() {
        String text="namespace api; import model.*; repository TourRepository for Tour { find Tours named(Title title) where title = :title order by title; }";
        var symbols=symbols(text);
        var field=symbols.definition(repo,at(text,"where tit"));
        assertEquals(model.toUri().toString(),field.getFirst().getUri());
        var parameter=symbols.definition(repo,at(text,":tit"));
        assertEquals(repo.toUri().toString(),parameter.getFirst().getUri());
        assertEquals(text.indexOf("Title title")+6,parameter.getFirst().getRange().getStart().getCharacter());
    }
}
