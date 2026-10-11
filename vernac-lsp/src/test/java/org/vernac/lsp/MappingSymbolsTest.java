package org.vernac.lsp;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MappingSymbolsTest {
    @Test void mappingTypesFieldsAndNamedConversionsUseTheirCorrectScope() {
        Path root=Path.of("mapping-workspace").toAbsolutePath(),path=root.resolve("demo/model.vernac");
        String text="namespace demo; value Title(String); port Remote { void ping(); } adapter Client implements Remote { schema Dto(String label); behavior { public void ping() {} } mapping toTitle: Title <- Dto { string <- source.label using trim; } mapping trim: String <- String { java { return source.strip(); } } }";
        var symbols=new VernacProjectSymbols(root,Map.of(path,text));
        int source=text.indexOf("source.label");
        assertEquals(List.of("source.label"),symbols.complete(path,new Position(0,source+8)).stream().map(c->c.getLabel()).toList());
        assertEquals(path.toUri().toString(),symbols.definition(path,new Position(0,source+8)).getFirst().getUri());
        int target=text.indexOf("string <-");
        assertEquals(List.of("string"),symbols.complete(path,new Position(0,target+1)).stream().map(c->c.getLabel()).toList());
        int using=text.indexOf("using trim")+6;
        assertEquals(List.of("trim"),symbols.complete(path,new Position(0,using+2)).stream().map(c->c.getLabel()).toList());
        assertEquals(text.indexOf("mapping trim")+8,symbols.definition(path,new Position(0,using+1)).getFirst().getRange().getStart().getCharacter());
        int dto=text.indexOf("<- Dto")+3;
        assertEquals(text.indexOf("schema Dto")+7,symbols.definition(path,new Position(0,dto+1)).getFirst().getRange().getStart().getCharacter());
    }
}
