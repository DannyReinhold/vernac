// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.symbols.BuiltinTypes;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VernacProjectCompilationTest {
    @TempDir Path root;
    private final VernacCompiler compiler = new VernacCompiler();

    private void source(String namespace, String file, String body) throws IOException {
        Path path = root.resolve(namespace.replace('.', '/')).resolve(file + ".vernac");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "namespace " + namespace + ";\n" + body);
    }

    @Test
    void compilesMultipleFilesAndNamespacesTogetherWithAJavaCaller() throws Exception {
        source("org.shared", "types", "value Title(String value); id OwnerId;");
        source("org.tasks", "names", "value TaskName(String value);");
        source("org.tasks", "model", """
                import org.shared.Title;
                import org.shared.OwnerId;
                value Task(TaskName name, Title title, OwnerId owner);
                """);
        var result = compiler.compileProject(root);
        assertEquals(4, result.generatedFiles().size());
        var caller = JavaFile.builder("client", TypeSpec.classBuilder("Caller")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodSpec.methodBuilder("run").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(String.class).addCode("""
                                return org.tasks.domain.Task.of(
                                    org.tasks.domain.TaskName.of("demo"),
                                    org.shared.domain.Title.of("hello"),
                                    org.shared.domain.OwnerId.create()).title().value();
                                """).build()).build()).build();
        List<JavaFile> files = new ArrayList<>(result.generatedFiles());
        files.add(caller);
        var compiled = InMemoryJavaCompiler.compile(files);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        assertEquals("hello", compiled.loadClass("client.Caller").getMethod("run").invoke(null));
        Path output = root.resolve("generated");
        result.writeTo(output);
        assertTrue(Files.exists(output.resolve("org/tasks/domain/Task.java")));
        assertTrue(Files.exists(output.resolve("org/shared/domain/Title.java")));
        assertFalse(Files.exists(output.resolve("org/tasks/domain/package-info.java")));
    }

    @Test
    void handlesEqualSimpleNamesWithoutConflictingJavaImports() throws Exception {
        source("org.left", "types", "value Name(String value);");
        source("org.right", "types", "value Name(String value);");
        source("org.pairs", "model", "value Pair(org.left.Name left, org.right.Name? right);");
        var compiled = InMemoryJavaCompiler.compile(compiler.compileProject(root).generatedFiles());
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        var leftType = compiled.loadClass("org.left.domain.Name");
        var rightType = compiled.loadClass("org.right.domain.Name");
        var pairType = compiled.loadClass("org.pairs.domain.Pair");
        var left = leftType.getMethod("of", String.class).invoke(null, "left");
        var right = rightType.getMethod("of", String.class).invoke(null, "right");
        var pair = pairType.getMethod("of", leftType, rightType).invoke(null, left, right);
        assertEquals(left, pairType.getMethod("left").invoke(pair));
        assertEquals(Optional.of(right), pairType.getMethod("right").invoke(pair));
        var requiredOnly = pairType.getMethod("of", leftType).invoke(null, left);
        assertEquals(Optional.empty(), pairType.getMethod("right").invoke(requiredOnly));
    }

    @Test
    void compilesEveryApprovedBuiltinFieldType() throws Exception {
        List<String> fields = new ArrayList<>();
        int index = 0;
        for (String builtin : BuiltinTypes.names()) fields.add(builtin + " field" + index++);
        source("org.scalars", "model", "value Scalars(" + String.join(", ", fields) + ");");
        var result = compiler.compileProject(root);
        var compiled = InMemoryJavaCompiler.compile(result.generatedFiles());
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        assertEquals(BuiltinTypes.names().size(), compiled.loadClass("org.scalars.domain.Scalars").getDeclaredFields().length);
    }

    @Test
    void namespaceEndingInDomainGetsNoSpecialTreatment() throws Exception {
        source("org.model.domain", "types", "id Key; value Name(String value); value Entry(Key key, Name name);");
        var result = compiler.compileProject(root);
        assertTrue(result.generatedFiles().stream().allMatch(file -> file.packageName().equals("org.model.domain.domain")));
        assertTrue(InMemoryJavaCompiler.compile(result.generatedFiles()).success());
    }

    @Test
    void refusesPartialGenerationOfUnreviewedDeclarationKinds() throws Exception {
        source("org.model", "types", "id Key; entity Item[Key](String name);");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.compileProject(root));
        assertTrue(error.getMessage().contains("Project generation for ENTITY"));
    }

    @Test
    void preservesImportWarningsInTheCompilationResult() throws Exception {
        source("org.shared", "types", "value Name(String value);");
        source("org.model", "types", "import org.shared.Name; import org.shared.Name; value Entry(Name name);");
        var result = compiler.compileProject(root);
        assertEquals(1, result.diagnostics().size());
        assertEquals(2, result.generatedFiles().size());
    }
}
