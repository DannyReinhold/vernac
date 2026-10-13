// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InboundErrorPolicySymbolsTest {
    @Test void enumCodesNavigateAndIntegrationCategoriesComplete() {
        Path root=Path.of("policy-workspace").toAbsolutePath(),path=root.resolve("demo/model.vernac");
        String text="namespace demo; value Reason = CLOSED; usecase Run() behavior {execute {}} adapter Api inbound http uses Run run {base \"/api\"; call {POST \"/\";execute run {} respond 204; errors {when rejection Reason.CLOSED then respond 409;}} errors {when integration remote TIMEOUT then respond 503;}}";
        var symbols=new VernacProjectSymbols(root,Map.of(path,text));
        var locations=symbols.definition(path,new Position(0,text.indexOf("Reason.CLOSED")+9));
        assertFalse(locations.isEmpty());assertEquals(text.indexOf("CLOSED"),locations.getFirst().getRange().getStart().getCharacter());
        assertTrue(symbols.complete(path,new Position(0,text.indexOf("TIMEOUT")+2)).stream().anyMatch(c->c.getLabel().equals("TIMEOUT")));
        assertTrue(symbols.complete(path,new Position(0,text.indexOf("Reason.CLOSED")+9)).stream().anyMatch(c->c.getLabel().equals("Reason.CLOSED")));
    }
}
