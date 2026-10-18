package org.vernac.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps the public entry points tied to compilable, executable language examples. */
class DocumentationExamplesTest {
    @TempDir Path temporary;

    @Test void diagramTutorialShowsTheActualGeneratedGraph() throws Exception {
        String document=Files.readString(Path.of("../docs/tutorials/domain-diagrams.md")).replace("\r\n","\n");
        var model=Pattern.compile("```vernac\\n(.*?)```",Pattern.DOTALL).matcher(document);
        var graph=Pattern.compile("```mermaid\\n(.*?)```",Pattern.DOTALL).matcher(document);
        assertTrue(model.find());assertTrue(graph.find());
        Path file=temporary.resolve("example/diagrams/model.vernac");Files.createDirectories(file.getParent());Files.writeString(file,model.group(1));
        var resolved=new VernacCompiler().analyzeProject(temporary);
        assertEquals(org.vernac.compiler.diagram.DomainDiagrams.mermaid(org.vernac.compiler.diagram.DomainDiagrams.model(resolved,"example.diagrams")),graph.group(1));
    }

    @Test void persistentModuleTutorialHasACompleteExportableProducerModel() throws Exception {
        String document=Files.readString(Path.of("../docs/tutorials/type-modules.md")).replace("\r\n","\n");
        var blocks=Pattern.compile("```vernac\\n(.*?)```",Pattern.DOTALL).matcher(document);String model=null;
        while(blocks.find())model=blocks.group(1);
        assertNotNull(model);assertTrue(model.contains("namespace example.billing;"));
        Path root=temporary.resolve("persistent-module-doc"),source=root.resolve("example/billing/invoice.vernac");Files.createDirectories(source.getParent());Files.writeString(source,model);
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root);var generated=compiler.generate(resolved,org.vernac.compiler.persistence.SchemaModel.empty(),java.util.Map.of());
        var manifests=Pattern.compile("```vernac-module\\n(.*?)```",Pattern.DOTALL).matcher(document);String manifest=null;
        while(manifests.find())manifest=manifests.group(1);
        assertNotNull(manifest);
        var descriptor=org.vernac.compiler.modules.ModuleContracts.publish(org.vernac.compiler.modules.ModuleDefinition.parse(root.resolve("vernac-module.vmodule"),manifest),resolved,generated.generatedFiles(),"example","billing","1",List.of());
        assertTrue(descriptor.persistent());assertEquals(5,descriptor.exports().size());
    }

    @Test void completeTutorialModelsResolveAndGenerate() throws Exception {
        for (String name : List.of("domain-diagrams", "ubiquitous-language", "repository-queries", "usecases", "enum-value-objects",
                "outbound-ports", "declarative-rest", "declarative-errors", "default-names",
                "adapter-dtos", "domain-events", "http-adapter-runtime", "collections",
                "your-first-domain-model", "what-is-ddd", "ddd-for-small-projects", "adapter-mappings", "inbound-http", "inbound-mvc", "pagination")) {
            String document = Files.readString(Path.of("../docs/tutorials/" + name + ".md"))
                    .replace("\r\n", "\n");
            var block = Pattern.compile("```vernac\\n(.*?)```", Pattern.DOTALL).matcher(document);
            assertTrue(block.find(), name + " needs a complete introductory model");
            String model = block.group(1);
            var namespace = Pattern.compile("namespace ([\\p{L}\\p{N}_.]+);").matcher(model);
            assertTrue(namespace.find(), name + " needs a namespace");
            Path root = temporary.resolve(name);
            Path file = root.resolve(namespace.group(1).replace('.', '/')).resolve("model.vernac");
            Files.createDirectories(file.getParent());
            Files.writeString(file, model);
            assertFalse(new VernacCompiler().compileProject(root).generatedFiles().isEmpty(), name);
        }
    }

    @Test void paginationTutorialGeneratesCompilableJava() throws Exception {
        String document = Files.readString(Path.of("../docs/tutorials/pagination.md")).replace("\r\n", "\n");
        var block = Pattern.compile("```vernac\\n(.*?)```", Pattern.DOTALL).matcher(document);
        assertTrue(block.find());
        Path root = temporary.resolve("pagination-model");
        Path file = root.resolve("example/paging/names.vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, block.group(1));
        Path sources = temporary.resolve("pagination-java");
        new VernacCompiler().compileProject(root).writeTo(sources);
        Path classes = Files.createDirectories(temporary.resolve("pagination-classes"));
        var arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(String[]::new)), "Pagination tutorial Java must compile");
    }

    @Test void dispatchPaginationModelAndItsGeneratedJavaCompile() throws Exception {
        Path sources=temporary.resolve("dispatch-java");
        var compiler=new VernacCompiler();
        Path modelRoot=Path.of("../examples/dispatch/src/main/vernac");
        var snapshot=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(Path.of("../examples/dispatch/src/main/vernac-schema/history/V20261011190000000__dispatch.json")));
        var previous=org.vernac.compiler.persistence.SchemaModel.parse(snapshot.get("model").toString());
        var current=new org.vernac.compiler.persistence.SchemaBuilder(compiler.analyzeProject(modelRoot),previous).build();
        assertEquals(previous.fingerprint(),current.fingerprint(),"Pagination must not change the existing Dispatch schema");
        compiler.compileProject(modelRoot).writeTo(sources);
        // The model references this external extension; Boot application wiring is built by the standalone example.
        Path extension=sources.resolve("showcase/integration/JournalImplementation.java");Files.createDirectories(extension.getParent());
        Files.copy(Path.of("../examples/dispatch/src/main/java/showcase/integration/JournalImplementation.java"),extension);
        Path classes=Files.createDirectories(temporary.resolve("dispatch-classes"));
        var arguments=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var files=Files.walk(sources)) { files.filter(path->path.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add); }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)),"Dispatch pagination endpoint and usecase must compile with the actual model");
    }

    @Test void aiReferenceExamplesCompileTogetherIncludingHandwrittenJava() throws Exception {
        String document = Files.readString(Path.of("../docs/ai-language-reference.md")).replace("\r\n", "\n");
        Path root = temporary.resolve("ai-model");
        var blocks = Pattern.compile("```vernac\\n(.*?)```", Pattern.DOTALL).matcher(document);
        int count = 0;
        while (blocks.find()) {
            String model = blocks.group(1);
            var namespace = Pattern.compile("namespace ([\\p{L}\\p{N}_.]+);").matcher(model);
            assertTrue(namespace.find(), "AI reference examples must be complete models");
            Path file = root.resolve(namespace.group(1).replace('.', '/')).resolve("example" + count++ + ".vernac");
            Files.createDirectories(file.getParent());
            Files.writeString(file, model);
        }
        assertTrue(count > 0, "AI reference must contain examples");
        Path sources = temporary.resolve("ai-java");
        new VernacCompiler().compileProject(root).writeTo(sources);
        var java = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL).matcher(document);
        while (java.find()) {
            String source = java.group(1);
            var packageName = Pattern.compile("package ([\\p{L}\\p{N}_.]+);").matcher(source);
            var typeName = Pattern.compile("public (?:final )?class (\\w+)").matcher(source);
            assertTrue(packageName.find(), "Java examples must include their package");
            assertTrue(typeName.find(), "Java examples must include their class");
            Path file = sources.resolve(packageName.group(1).replace('.', '/')).resolve(typeName.group(1) + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        }
        Path classes = Files.createDirectories(temporary.resolve("ai-classes"));
        var arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(String[]::new)), "AI reference Java must compile");
    }

    @Test void dddBeginnerModelsGenerateCompilableJava() throws Exception {
        for (String name : List.of("what-is-ddd", "ddd-for-small-projects")) {
            String document = Files.readString(Path.of("../docs/tutorials/" + name + ".md"))
                    .replace("\r\n", "\n");
            var block = Pattern.compile("```vernac\\n(.*?)```", Pattern.DOTALL).matcher(document);
            assertTrue(block.find(), name + " needs its complete example");
            Path root = temporary.resolve(name + "-java");
            Path model = root.resolve("vernac/example/tasks/model.vernac");
            Files.createDirectories(model.getParent());
            Files.writeString(model, block.group(1));
            Path sources = root.resolve("java");
            new VernacCompiler().compileProject(root.resolve("vernac")).writeTo(sources);
            Path classes = Files.createDirectories(root.resolve("classes"));
            var arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-classpath",
                    System.getProperty("java.class.path"), "-d", classes.toString()));
            try (var files = Files.walk(sources)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .map(Path::toString).forEach(arguments::add);
            }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    arguments.toArray(String[]::new)), name + " must generate compilable Java");
        }
    }

    @Test void entryPointsTellTheSameExecutableStory() throws Exception {
        String expected = null;
        for (String page : List.of("README.md", "docs/index.md",
                "docs/tutorials/from-model-to-application.md")) {
            String document = Files.readString(Path.of("..").resolve(page)).replace("\r\n", "\n");
            var match = Pattern.compile("```vernac\\n(.*?)```", Pattern.DOTALL).matcher(document);
            assertTrue(match.find(), page + " needs its complete model");
            String model = match.group(1);
            if (expected == null) expected = model;
            else assertEquals(expected, model, "Entry points must not drift: " + page);
        }
        Path root = temporary.resolve("model");
        Path file = root.resolve("example/dispatch/tour.vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, expected);
        Path sources = temporary.resolve("java");
        new VernacCompiler().compileProject(root).writeTo(sources);
        Path probe = sources.resolve("example/dispatch/StoryProbe.java");
        Files.createDirectories(probe.getParent());
        Files.writeString(probe, """
                package example.dispatch;
                import example.dispatch.domain.*;
                public final class StoryProbe {
                    public static void run() {
                        var title = Title.of("Morning delivery");
                        var tour = Tour.create(title, TourStatus.PLANNED);
                        var id = tour.id();
                        tour.complete();
                        if (tour.tourStatus() != TourStatus.COMPLETED || !id.equals(tour.id()))
                            throw new AssertionError("Completion must retain identity");
                        try {
                            Title.of(" ");
                            throw new AssertionError("Blank title accepted");
                        } catch (org.vernac.runtime.DomainValidationException expected) { }
                        var first = TourCompleted.create(id);
                        var second = TourCompleted.create(id);
                        if (first.equals(second) || !first.tourId().equals(id))
                            throw new AssertionError("Events must represent distinct occurrences");
                    }
                }
                """);
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        var arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString).forEach(arguments::add);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(String[]::new)), "Documentation Java must compile");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            loader.loadClass("example.dispatch.StoryProbe").getMethod("run").invoke(null);
        }
    }
}
