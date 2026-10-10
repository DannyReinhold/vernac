package org.vernac.compiler.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryBindingTest {
    @TempDir Path root;
    @Test void repositoryUsesResolvedCrossNamespaceTypesAndCapturedStorageNames() throws Exception {
        Files.createDirectories(root.resolve("model")); Files.createDirectories(root.resolve("storage"));
        Files.writeString(root.resolve("model/a.vernac"),"""
            namespace model;
            id RootId; id ItemId;
            value Text(String);
            entity Item[ItemId](Text) list Items;
            aggregate Root[RootId](Items, Item? preferred);
            """);
        Files.writeString(root.resolve("storage/r.vernac"),"""
            namespace storage;
            import model.Root;
            repository Roots for Root { }
            """);
        var result=new VernacCompiler().compileProject(root);
        String generated=result.generatedFiles().stream().filter(f->f.typeSpec().name().equals("JdbcRoots")).findFirst().orElseThrow().toString();
        assertTrue(generated.contains("package storage.adapter.outbound.jdbc;"));
        assertTrue(generated.contains("import model.domain.Root;"));
        assertTrue(generated.contains("Root.@entity:model.Item"));
        assertTrue(generated.contains("Propagation.MANDATORY"));
        assertFalse(generated.contains("PostgresSchemaUtils"));
    }
    @Test void singleSourceEntryPointAlsoUsesReviewedRepositoryBindings() {
        var result=new VernacCompiler().compileSource("""
            namespace model;
            id RootId; value Text(String); aggregate Root[RootId](Text);
            repository Roots for Root { }
            """);
        assertTrue(result.generatedFiles().stream().anyMatch(f->f.toString().contains("JdbcAggregateStore")));
    }
    @Test void repositoryUsesTheSameEnumOverridesAsTheSchema() throws Exception {
        Files.createDirectories(root.resolve("model"));
        Files.writeString(root.resolve("model/model.vernac"), """
            namespace model;
            id RootId;
            value State = READY;
            aggregate Root[RootId](State);
            repository Roots for Root { }
            """);
        var result = new VernacCompiler().compileProject(root, SchemaModel.empty(),
                java.util.Map.of("model.State", java.util.Map.of("READY", "stable-ready-code")));
        String adapter = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("JdbcRoots"))
                .findFirst().orElseThrow().toString();
        assertTrue(adapter.contains("case READY -> \"stable-ready-code\""));
        assertTrue(adapter.contains("case \"stable-ready-code\" -> State.READY"));
    }

    @Test void unsupportedQueryMethodsAreDiagnosedInsteadOfSilentlyIgnored() {
        var error = assertThrows(org.vernac.compiler.analyzer.SemanticValidationException.class,
                () -> new VernacCompiler().compileSource("""
                    namespace model;
                    id RootId; value Text(String); aggregate Root[RootId](Text);
                    repository Roots for Root { find List<Root> findByText(String text); }
                    """));
        assertTrue(error.getMessage().contains("not Java generics"));
        assertTrue(error.getMessage().contains("explicitly declared"));
    }
}
