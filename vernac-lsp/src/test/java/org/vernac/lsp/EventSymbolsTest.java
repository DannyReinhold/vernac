// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventSymbolsTest {
    final Path root=Path.of("events-workspace").toAbsolutePath();
    final Path domain=root.resolve("model/events.vernac"), listener=root.resolve("handlers/listener.vernac");
    final String events="namespace model; id TourId; value Title(String); event Changed(Title);";
    @Test void completesOnlyEventTargetsAndNavigatesAcrossNamespaces() {
        String source="namespace handlers; import model.*; synchronous listener Observe on Changed behavior { execute {} }";
        var symbols=new VernacProjectSymbols(root,Map.of(domain,events,listener,source));
        var at=new Position(0,source.indexOf("on Changed")+5);
        var choices=symbols.complete(listener,at);
        assertNotNull(choices);
        assertEquals(java.util.List.of("Changed"),choices.stream().map(c->c.getLabel()).toList());
        var target=symbols.definition(listener,at);
        assertEquals(domain.toUri().toString(),target.getFirst().getUri());
    }
    @Test void eventPayloadCompletionExcludesRawScalarsAndEvents() {
        String source="namespace handlers; import model.*; event Other(T);";
        var symbols=new VernacProjectSymbols(root,Map.of(domain,events,listener,source));
        var choices=symbols.complete(listener,new Position(0,source.indexOf("(T")+2));
        assertEquals(java.util.List.of("Title","TourId"),choices.stream().map(c->c.getLabel()).toList());
    }
}
