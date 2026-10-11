// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import java.nio.file.Path;
import java.util.*;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestSymbolsTest {
    @Test void errorRulesNavigateDtosNestedFieldsAndImportedEnumConstants() {
        Path root = Path.of("error-workspace").toAbsolutePath(), path = root.resolve("demo/model.vernac");
        Path domain = root.resolve("codes/codes.vernac");
        String text = "namespace demo; import codes.Rejection; value Title(String); port Remote { Title load(); } "
            + "adapter Client implements Remote { rest { client http; base baseUri; Title load() { GET \"/\"; response Payload using toTitle; "
            + "errors { response Problem; when response.meta.reason = \"x\" then reject Rejection.DENIED \"No\"; "
            + "when status = 500 then fail remote REMOTE_FAILURE \"Down\"; } } } "
            + "schema Payload(String title); schema Problem(Meta? meta); schema Meta(String reason); "
            + "mapping toTitle: Title <- Payload { string <- source.title; } }";
        String code = "namespace codes; value Rejection = DENIED | OTHER;";
        var symbols = new VernacProjectSymbols(root, Map.of(path, text, domain, code));
        int field = text.indexOf("response.meta.reason");
        assertEquals(List.of("response.meta.reason"), symbols.complete(path, new Position(0, field + 18)).stream().map(c -> c.getLabel()).toList());
        assertFalse(symbols.definition(path, new Position(0, field + 17)).isEmpty());
        int reject = text.indexOf("Rejection.DENIED");
        assertTrue(symbols.complete(path, new Position(0, reject + 12)).stream().anyMatch(c -> c.getLabel().equals("Rejection.DENIED")));
        assertEquals(domain.toUri().toString(), symbols.definition(path, new Position(0, reject + 12)).getFirst().getUri());
        int schema = text.indexOf("response Problem") + 9;
        assertEquals(text.indexOf("schema Problem") + 7, symbols.definition(path, new Position(0, schema + 2)).getFirst().getRange().getStart().getCharacter());
        int category = text.indexOf("REMOTE_FAILURE");
        assertTrue(symbols.complete(path, new Position(0, category + 5)).stream().anyMatch(c -> c.getLabel().equals("REMOTE_FAILURE")));
    }
    @Test void completesAndNavigatesOperationPathsMappingsAndResponseSchemas() {
        Path root = Path.of("rest-workspace").toAbsolutePath(), path = root.resolve("demo/model.vernac");
        Path values = root.resolve("demo/values.vernac");
        String text = "namespace demo; port Remote { Title load(Lookup lookup); } adapter Client implements Remote { rest { client http; base baseUri; Title load(Lookup lookup) { GET \"/items/{key}\"; path \"key\" <- lookup.key; response Payload using toTitle; } } schema Payload(String title); mapping toTitle: Title <- Payload { string <- source.title; } }";
        String domain = "namespace demo; value Title(String); value Lookup(Title key);";
        var symbols = new VernacProjectSymbols(root, Map.of(path, text, values, domain));
        int field = text.indexOf("lookup.key");
        assertEquals(List.of("lookup.key"), symbols.complete(path, new Position(0, field + 9)).stream().map(c -> c.getLabel()).toList());
        assertEquals(values.toUri().toString(), symbols.definition(path, new Position(0, field + 8)).getFirst().getUri());
        assertEquals(List.of("lookup"), symbols.complete(path, new Position(0, field + 3)).stream().map(c -> c.getLabel()).toList());
        assertEquals(path.toUri().toString(), symbols.definition(path, new Position(0, field + 3)).getFirst().getUri());
        int using = text.indexOf("using toTitle") + 6;
        assertEquals(List.of("toTitle"), symbols.complete(path, new Position(0, using + 3)).stream().map(c -> c.getLabel()).toList());
        assertEquals(text.indexOf("mapping toTitle") + 8,
                symbols.definition(path, new Position(0, using + 2)).getFirst().getRange().getStart().getCharacter());
        int dto = text.indexOf("response Payload") + 9;
        assertEquals(List.of("Payload"), symbols.complete(path, new Position(0, dto + 3)).stream().map(c -> c.getLabel()).toList());
        assertEquals(text.indexOf("schema Payload") + 7,
                symbols.definition(path, new Position(0, dto + 2)).getFirst().getRange().getStart().getCharacter());
    }
}
