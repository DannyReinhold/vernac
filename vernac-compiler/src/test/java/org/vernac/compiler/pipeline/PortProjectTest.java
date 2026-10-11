package org.vernac.compiler.pipeline;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.tooling.*;
import static org.assertj.core.api.Assertions.*;
class PortProjectTest {
    @TempDir Path root;
    @Test void resolvesAcrossFilesAndProjectsJavaWithTheAdapterReceiver() throws Exception {
        Files.createDirectories(root.resolve("domain"));Files.createDirectories(root.resolve("infra"));
        Path model=root.resolve("domain/model.vernac"), port=root.resolve("domain/port.vernac"), adapter=root.resolve("infra/adapter.vernac");
        String modelText="namespace domain; value Text(String);";
        String portText="namespace domain; port Archive { Text load(Text); }";
        String text="namespace infra; import domain.*; adapter LocalArchive implements Archive { behavior { public Text load(Text) { return text; } } }";
        Files.writeString(model,modelText);Files.writeString(port,portText);Files.writeString(adapter,text);
        var compiler=new VernacCompiler();
        assertThat(compiler.compileProject(root).generatedFiles().toString()).contains("implements Archive");
        var project=compiler.readProject(root);
        var source=project.sources().stream().filter(s->s.path().equals(adapter)).findFirst().orElseThrow();
        var blocks=new BehaviorJavaProjection().build(project,source,text);
        assertThat(blocks.toString()).contains("infra.adapter.outbound.access.LocalArchiveAccess self");
        var navigation=new VernacTypeNavigation();
        var texts=Map.of(model,modelText,port,portText,adapter,text);
        assertThat(navigation.find(project,texts,"infra.adapter.outbound.LocalArchive")).isPresent();
        assertThat(navigation.findMember(project,texts,"domain.domain.Archive",new VernacTypeNavigation.Member("load",List.of("domain.domain.Text"))))
            .isPresent();
    }
}
