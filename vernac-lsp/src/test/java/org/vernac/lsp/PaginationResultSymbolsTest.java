// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import java.nio.file.Path;
import java.util.Map;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaginationResultSymbolsTest {
    @Test void resultFieldsAndDtoPageFieldsNavigateAndComplete() {
        Path root=Path.of("page-workspace").toAbsolutePath(),file=root.resolve("demo/model.vernac");
        String text="namespace demo; value Text(String) list Texts; usecase Board() returns (counted offset Texts first) behavior {execute {throw new UnsupportedOperationException();}} adapter Api inbound http uses Board board {base \"/\";get {GET \"/\";execute board {} respond 200 Reply using output;} schema Reply(counted offset list<String> first);mapping output:Reply <- Board.Result {first <- source.first;}}";
        var symbols=new VernacProjectSymbols(root,Map.of(file,text));
        int usecaseField=text.indexOf("Texts first")+6;
        var definition=symbols.definition(file,new Position(0,text.indexOf("source.first")+9));
        assertFalse(definition.isEmpty());assertEquals(usecaseField,definition.getFirst().getRange().getStart().getCharacter());
        var choices=symbols.complete(file,new Position(0,text.indexOf("source.first")+10));
        assertNotNull(choices);assertTrue(choices.stream().anyMatch(c->c.getLabel().endsWith("first")));
        int dtoField=text.indexOf("list<String> first")+13;
        var target=symbols.definition(file,new Position(0,text.indexOf("{first <-")+3));
        assertFalse(target.isEmpty());assertEquals(dtoField,target.getFirst().getRange().getStart().getCharacter());
    }
}
