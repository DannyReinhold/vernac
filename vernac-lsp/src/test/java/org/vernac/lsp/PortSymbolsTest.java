package org.vernac.lsp;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PortSymbolsTest {
    final Path root=Path.of("ports-workspace").toAbsolutePath();
    final Path domain=root.resolve("model/ports.vernac"), adapter=root.resolve("infra/adapter.vernac");
    final String model="namespace model; value Text(String); port Archive { Text load(); }";
    @Test void completesOnlyPortsAndNavigatesImplementationTarget() {
        String source="namespace infra; import model.*; adapter LocalArchive implements Archive { behavior { public Text load() { return Text.of(\"x\"); } } }";
        var symbols=new VernacProjectSymbols(root,Map.of(domain,model,adapter,source));
        var at=new Position(0,source.indexOf("implements Archive")+13);
        assertEquals(List.of("Archive"),symbols.complete(adapter,at).stream().map(c->c.getLabel()).toList());
        assertEquals(domain.toUri().toString(),symbols.definition(adapter,at).getFirst().getUri());
    }
    @Test void portPayloadCompletionExcludesRawScalarsAndPorts() {
        String source="namespace infra; import model.*; port Second { T load(); }";
        var symbols=new VernacProjectSymbols(root,Map.of(domain,model,adapter,source));
        assertEquals(List.of("Text"),symbols.complete(adapter,new Position(0,source.indexOf(" T load")+2)).stream().map(c->c.getLabel()).toList());
    }
}
