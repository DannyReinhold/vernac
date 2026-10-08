// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.tooling.BehaviorJavaProjection;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorJavaProjectionTest {
    private List<BehaviorJavaProjection.Block> project(String text, String... others) {
        Path root = Path.of(".").toAbsolutePath();
        var parser = new VernacSourceParser();
        var file = new VernacSourceFile(root.resolve("source.vernac"), parser.parse("source.vernac", text));
        var sources = new ArrayList<VernacSourceFile>();
        sources.add(file);
        for (int i = 0; i < others.length; i++) sources.add(new VernacSourceFile(root.resolve("other" + i + ".vernac"),
                parser.parse("other" + i + ".vernac", others[i])));
        var project = new VernacProjectLoader().index(root, sources);
        return new BehaviorJavaProjection().build(project, file, text);
    }
    private String java(String source, BehaviorJavaProjection.Block block) {
        StringBuilder result = new StringBuilder();
        for (var fragment : block.fragments()) result.append(fragment.prefix())
                .append(source, fragment.start(), fragment.end()).append(fragment.suffix());
        return result.toString();
    }

    @Test void validationFragmentsUseReadSelfAndUtf16Ranges() {
        String text = """
            // 😀 before validation
            namespace model;
            value Größe(String) validates {
                require(!self.string().isBlank(), "blank");
                require(self.string().toUpperCase(Locale.ROOT).length() > 0, "length");
            } behavior { java imports { java.util.Locale; } };
            """;
        var block = project(text).getFirst();
        assertEquals("GrößeValidation", block.owner());
        assertEquals("!self.string().isBlank()", text.substring(block.fragments().getFirst().start(), block.fragments().getFirst().end()));
        String code = java(text, block);
        assertTrue(code.contains("model.domain.GrößeRead self"));
        Map<String,String> sources = new LinkedHashMap<>();
        new VernacCompiler().compileSource(text).generatedFiles().forEach(f -> sources.put(f.packageName()+"."+f.typeSpec().name(), f.toString()));
        sources.put("model.domain.__VernacEditorValidation_Größe", code);
        var result = org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(sources);
        assertTrue(result.success(), result.diagnostics().toString());
    }

    @Test void methodsShareOneContextAndOriginalDeclarationNames() {
        String text = """
                namespace model;
                value Name(String) behavior {
                    java imports { java.util.Locale; }
                    public String upper() { return self.string().toUpperCase(Locale.ROOT) + suffix(); }
                    private String suffix() { return "!"; }
                    public String? alternative(String? candidate) { return Optional.ofNullable(candidate); }
                };
                """;
        var blocks = project(text);
        assertEquals(1, blocks.size());
        var block = blocks.getFirst();
        String code = java(text, block);
        assertTrue(code.contains("import java.util.Locale;"));
        assertTrue(code.contains("static java.lang.String upper(model.domain.Name self)"));
        assertTrue(code.contains("private static java.lang.String suffix()"));
        assertTrue(code.contains("java.util.Optional<java.lang.String> alternative(model.domain.Name self, java.lang.@org.jspecify.annotations.Nullable String candidate)"));
        assertEquals("suffix", text.substring(block.fragments().get(2).start(), block.fragments().get(2).end()));
        assertTrue(code.contains("+ suffix();"));
        Map<String, String> sources = new LinkedHashMap<>();
        new VernacCompiler().compileSource(text).generatedFiles().forEach(f ->
                sources.put(f.packageName() + "." + f.typeSpec().name(), f.toString()));
        sources.put("model.domain.__VernacEditorBehavior_Name", code);
        var compiled = org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(sources);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
    }

    @Test void unicodeAndBracesInStringsPreserveExactHostRanges() {
        String text = """
                // 😀 Supplementary characters before the injected code
                namespace größe;
                value Größe(String) behavior {
                    public String größer() { return "😀 { }" + self.string(); /* } */ }
                    private String leer() {}
                };
                """;
        var block = project(text).getFirst();
        assertEquals("größer", text.substring(block.fragments().getFirst().start(), block.fragments().getFirst().end()));
        assertTrue(java(text, block).contains("return \"😀 { }\" + self.string(); /* } */"));
        var emptyBody = block.fragments().get(3);
        assertEquals(emptyBody.start(), emptyBody.end());
    }

    @Test void collectionsAndEnumsHaveSeparateReceiversAndImportsDoNotLeak() {
        String text = """
                namespace model;
                value State = OPEN | CLOSED behavior {
                    java imports { java.util.Locale; }
                    public String label() { return self.name().toLowerCase(Locale.ROOT); }
                } list States behavior {
                    public int total() { return self.size(); }
                };
                id TaskId set TaskIds behavior { public boolean none() { return self.isEmpty(); } };
                """;
        var blocks = project(text);
        assertEquals(List.of("State", "States", "TaskIds"), blocks.stream().map(BehaviorJavaProjection.Block::owner).toList());
        assertTrue(java(text, blocks.get(1)).contains("model.domain.States self"));
        assertFalse(java(text, blocks.get(1)).contains("java.util.Locale"));
        assertTrue(java(text, blocks.get(2)).contains("model.domain.TaskIds self"));
    }

    @Test void usesCompilerNamespaceResolutionIncludingBodyOnlyReferences() {
        String text = """
                namespace view;
                import model.*;
                value Text(String) behavior {
                    public Name name() { return Name.of(self.string()); }
                };
                """;
        var code = java(text, project(text, "namespace model; value Name(String);").getFirst());
        assertTrue(code.contains("import model.domain.Name;"));
        assertTrue(code.contains("static model.domain.Name name(view.domain.Text self)"));
    }

    @Test void ambiguousAndConflictingImportsDoNotProduceMisleadingJavaContexts() {
        String text = "namespace view; import one.*; import two.*; value Text(String) behavior { public Name name() { return null; } };";
        assertTrue(project(text, "namespace one; value Name(String);", "namespace two; value Name(String);").isEmpty());
        assertTrue(project("namespace model; value Name(String) behavior { java imports { foreign.String; } public String label() { return null; } };").isEmpty());
    }

    @Test void externalOnlyBehaviorDoesNotInjectAnEmptyJavaFile() {
        assertTrue(project("namespace model; value Name(String) behavior { public String label() implemented by app.Impl; };").isEmpty());
    }

    @Test void entityBehaviorProjectsTheCorrectReceiverInterfaces() {
        String source = """
            namespace model;
            id TaskId; value Count(int);
            entity Task[TaskId](mut Count count) behavior {
                read int current() { return self.count().intValue(); }
                modify void increase() { self.count(Count.of(self.count().intValue() + 1)); }
                private int one() { return 1; }
            };
            """;
        var block = project(source).getFirst();
        String code = java(source, block);
        assertTrue(code.contains("current(model.domain.TaskRead self)"));
        assertTrue(code.contains("increase(model.domain.TaskAccess self)"));
        Map<String, String> sources = new LinkedHashMap<>();
        new VernacCompiler().compileSource(source).generatedFiles().forEach(f -> sources.put(f.packageName() + "." + f.typeSpec().name(), f.toString()));
        sources.put("model.domain.__VernacEditorBehavior_Task", code);
        var compiled = org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(sources);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
    }
}
