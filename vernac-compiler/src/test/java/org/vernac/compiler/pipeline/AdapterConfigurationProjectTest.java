package org.vernac.compiler.pipeline;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterConfigurationProjectTest {
  @TempDir Path root;

  private VernacProjectCompilationResult compile(String fields, String body) throws Exception {
    var folder = Files.createDirectories(root.resolve("example/configuration"));
    Files.writeString(
        folder.resolve("model.vernac"),
        "namespace example.configuration; value Text(String); port Archive { Text load(Text); }"
            + " adapter LocalArchive implements Archive { configuration { "
            + fields
            + " } behavior { public Text load(Text) { "
            + body
            + " } } }");
    return new VernacCompiler().compileProject(root);
  }

  @Test
  void typedConfigurationIsExposedOnAccessAndWiredFromSpringEnvironment() throws Exception {
    var code =
        compile(
                "String präfix from \"archive.prefix\"; URI endpoint from \"archive.url\"; Duration"
                    + " timeout from \"archive.timeout\"; Path directory from"
                    + " \"archive.directory\"; int limit from \"archive.limit\"; long size from"
                    + " \"archive.size\"; boolean enabled from \"archive.enabled\";",
                "return Text.of(self.präfix() + text.string());")
            .generatedFiles()
            .toString();
    assertThat(code)
        .contains(
            "String präfix()",
            "URI endpoint()",
            "Duration timeout()",
            "Path directory()",
            "int limit()",
            "long size()",
            "boolean enabled()",
            "@Autowired",
            "Environment __vernacEnvironment",
            "AdapterConfiguration.read(",
            "Integer.class");
  }

  @Test
  void rejectsUnsupportedTypesInvalidKeysAndMemberCollisions() {
    for (String fields :
        java.util.List.of(
            "Text prefix from \"archive.prefix\";",
            "String prefix from \"${archive.prefix}\";",
            "String self from \"archive.prefix\";",
            "String prefix from \"a\"; String prefix from \"b\";")) {
      assertThatThrownBy(() -> compile(fields, "return text;"))
          .isInstanceOf(org.vernac.compiler.analyzer.SemanticValidationException.class);
    }
  }

  @Test
  void rejectsRestBaseWithWrongTypeAndDependencyAccessorCollisions() throws Exception {
    var folder = Files.createDirectories(root.resolve("demo"));
    var file = folder.resolve("model.vernac");
    String rest =
        "namespace demo; port Remote { void ping(); } adapter Client implements Remote {"
            + " configuration { String endpoint from \"remote.url\"; } rest { client http; base"
            + " endpoint; void ping() { GET \"/\"; } } }";
    Files.writeString(file, rest);
    assertThatThrownBy(() -> new VernacCompiler().compileProject(root))
        .hasMessageContaining("REST base configuration must have type URI");
    Files.writeString(
        file, rest.replace("String endpoint", "URI http").replace("base endpoint", "base http"));
    assertThatThrownBy(() -> new VernacCompiler().compileProject(root))
        .hasMessageContaining("Duplicate");
    Files.writeString(
        file,
        "namespace demo; port Remote { void ping(); } adapter Client implements Remote {"
            + " configuration { String ping from \"remote.ping\"; } behavior { public void ping()"
            + " {} } }");
    assertThatThrownBy(() -> new VernacCompiler().compileProject(root))
        .hasMessageContaining("conflicts with dependency accessor");
  }

  @Test
  void configurationAccessNavigatesToTheVernacDeclaration() throws Exception {
    compile("String prefix from \"archive.prefix\";", "return Text.of(self.prefix());");
    var compiler = new VernacCompiler();
    var project = compiler.readProject(root);
    var file = root.resolve("example/configuration/model.vernac");
    var text = Files.readString(file);
    var navigation = new org.vernac.compiler.tooling.VernacTypeNavigation();
    var target =
        navigation.findMember(
            project,
            java.util.Map.of(file, text),
            "example.configuration.adapter.outbound.access.LocalArchiveAccess",
            new org.vernac.compiler.tooling.VernacTypeNavigation.Member(
                "prefix", java.util.List.of()));
    assertThat(target).isPresent();
    assertThat(text.substring(target.orElseThrow().start(), target.orElseThrow().end()))
        .isEqualTo("prefix");
    assertThat(
            navigation.find(
                project,
                java.util.Map.of(file, text),
                "example.configuration.adapter.outbound.access.LocalArchiveAccess"))
        .isEmpty();
  }
}
