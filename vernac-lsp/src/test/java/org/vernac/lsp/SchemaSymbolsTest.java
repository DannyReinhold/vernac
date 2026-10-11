package org.vernac.lsp;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SchemaSymbolsTest {
    @Test void localSchemaTypesCompleteAndNavigateWithoutLeakingToTheDomain() {
        Path root=Path.of("schema-workspace").toAbsolutePath(),path=root.resolve("demo/model.vernac");
        String text="namespace demo; value Secret(String); port Remote { void ping(); } adapter Client implements Remote { schema Response(list<Item> items); behavior { public void ping() {} } schema Item(String code); } adapter Other implements Remote { schema OtherResponse(int count); behavior { public void ping() {} } }";
        var symbols=new VernacProjectSymbols(root,Map.of(path,text));
        var at=new Position(0,text.indexOf("<Item>")+1);
        var choices=symbols.complete(path,at).stream().map(c->c.getLabel()).toList();
        assertTrue(choices.containsAll(List.of("Item","Response","String","int","list")));
        assertFalse(choices.contains("Secret")); assertFalse(choices.contains("OtherResponse"));
        var target=symbols.definition(path,new Position(0,at.getCharacter()+2)).getFirst();
        assertEquals(text.indexOf("schema Item")+7,target.getRange().getStart().getCharacter());
        String domain=text+" value Invalid(Item);";
        symbols=new VernacProjectSymbols(root,Map.of(path,domain));
        assertTrue(symbols.definition(path,new Position(0,domain.lastIndexOf("Item")+1)).isEmpty());
    }
}
