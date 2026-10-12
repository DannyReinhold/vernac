package org.vernac.lsp;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InboundHttpSymbolsTest {
    @Test void bindingsUsecaseArgumentsMappingsAndDtosCompleteAndNavigate() {
        Path root=Path.of("inbound-workspace").toAbsolutePath(),path=root.resolve("demo/model.vernac");
        String text="namespace demo; id Id; value Text(String); usecase Create(Text) returns Id behavior {execute {return Id.create();}} adapter Api inbound http uses Create create {base \"/api\"; post {POST \"/\";body Request;execute create {text <- body.name;} respond 201 Response using output;} schema Request(String name);schema Response(UUID id);mapping output:Response <- Id {id <- source;}}";
        var symbols=new VernacProjectSymbols(root,Map.of(path,text));
        assertEquals(text.indexOf("schema Request")+7,symbols.definition(path,new Position(0,text.indexOf("body Request")+7)).getFirst().getRange().getStart().getCharacter());
        assertTrue(symbols.complete(path,new Position(0,text.indexOf("body Request")+5)).stream().anyMatch(c->c.getLabel().equals("Request")));
        assertEquals(text.indexOf("String name")+7,symbols.definition(path,new Position(0,text.indexOf("body.name")+7)).getFirst().getRange().getStart().getCharacter());
        assertEquals(text.indexOf("uses Create create")+5,symbols.definition(path,new Position(0,text.indexOf("execute create")+10)).getFirst().getRange().getStart().getCharacter());
        assertTrue(symbols.complete(path,new Position(0,text.indexOf("text <-")+2)).stream().anyMatch(c->c.getLabel().equals("text")));
        assertEquals(text.indexOf("mapping output")+8,symbols.definition(path,new Position(0,text.indexOf("using output")+8)).getFirst().getRange().getStart().getCharacter());
    }
}
