// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;

import java.nio.file.*;
import java.util.*;

class EditorValueObjectsTest {
    @TempDir Path root;

    private VernacProject model(String text) throws Exception {
        Path file = root.resolve("model/values.vernac");
        return new VernacProjectLoader()
                .loadForAnalysis(
                        root, Map.of(file, "namespace model;\n" + text), new ArrayList<>());
    }

    private EditorValueObjects.Model view(String text) throws Exception {
        return new EditorValueObjects().build(model(text));
    }

    @Test
    void repositoriesListenersAndAdaptersExposeActualContractsWithoutSchemaPlanning() throws Exception {
        var project = model("""
                id TaskId;
                value Caption(String);
                aggregate Task[TaskId](Caption) list Tasks;
                repository TasksRepository for Task {
                    find Tasks withCaption(Caption caption) where caption = :caption order by createdAt;
                    find Task? matching(Caption caption) where caption = :caption;
                    find offset Tasks paged() order by createdAt, id counting;
                    find cursor Tasks following() order by createdAt, id;
                }
                event Changed(Caption);
                port Notices {void send(Caption);}
                adapter LocalNotices implements Notices {
                    schema Payload(String caption);
                    mapping payload: Payload <- Caption { caption <- source.string; }
                    behavior {public void send(Caption caption) {}}
                }
                synchronous listener Alert on Changed uses TasksRepository behavior {execute {}}
                """);
        var api = new EditorValueObjects().build(project);
        assertTrue(api.values().containsKey("model.domain.TasksRepository"), api.values().keySet().toString());
        String repository = api.values().get("model.domain.TasksRepository").javaSource();
        assertTrue(repository.contains("Task byId(TaskId id)"));
        assertTrue(repository.contains("Task save(Task aggregate)"));
        assertTrue(repository.contains("Tasks withCaption(Caption caption)"));
        assertTrue(repository.contains("Optional<Task> matching(Caption caption)"));
        assertTrue(repository.contains("CountedOffsetQuery<Task, Tasks> paged()"));
        assertTrue(repository.contains("CursorQuery<Task, Tasks> following()"));
        assertTrue(api.values().get("model.adapter.inbound.event.access.AlertAccess").javaSource()
                .contains("TasksRepository tasksRepository()"));
        assertTrue(api.values().get("model.adapter.outbound.LocalNotices").javaSource().contains("implements Notices"));
        assertTrue(api.values().containsKey("model.adapter.outbound.dto.LocalNoticesDtos"));
        assertTrue(api.values().containsKey("model.adapter.outbound.mapping.LocalNoticesMappings"));
        var generated = new VernacCompiler().generate(new ProjectTypeResolver().resolve(project),
                org.vernac.compiler.persistence.SchemaModel.empty(), Map.of());
        for (var file : generated.generatedFiles()) {
            String name = file.packageName() + "." + file.typeSpec().name();
            if (api.values().containsKey(name)) assertEquals(file.toString(), api.values().get(name).javaSource());
        }
        assertFalse(Files.exists(root.resolve("target")));
        assertTrue(api.values().keySet().stream().noneMatch(n -> n.contains("__Vernac")));
    }

    @Test
    void invalidRepositoryAndAdapterDoNotRemoveIndependentApis() throws Exception {
        var api = view("""
                value Caption(String);
                repository Broken for Missing {}
                port Notices {void send(Caption);}
                adapter Bad implements Missing {behavior {public void send(Caption caption){}}}
                event Changed(Caption);
                """);
        assertTrue(api.values().containsKey("model.domain.Changed"));
        assertFalse(api.values().containsKey("model.domain.Broken"));
        assertFalse(api.values().containsKey("model.adapter.outbound.Bad"));
    }

    @Test
    void servicesEventsPortsAndUsecasesExposeProductionApisWithoutCompanions() throws Exception {
        var project = model("""
                value Title(String);
                event Changed(Title) validates { require(!self.title().string().isBlank()); };
                port Notifications { void send(Title); }
                service Policy uses Notifications behavior {
                    public Title choose(Title title) { self.notifications().send(title); return title; }
                }
                usecase Rename(Title? title) returns (Title? title) uses Policy
                validates { require(self.title().isPresent()); }
                behavior { execute { return Result.of(title); } }
                """);
        var api = new EditorValueObjects().build(project);
        assertTrue(api.values().containsKey("model.domain.Changed"), api.values().keySet().toString());
        assertTrue(api.values().get("model.domain.Changed").javaSource().contains("reconstitute("));
        assertTrue(api.values().get("model.domain.Notifications").javaSource().contains("void send(Title title)"));
        assertTrue(api.values().get("model.domain.access.PolicyAccess").javaSource().contains("Notifications notifications()"));
        assertTrue(api.values().get("model.usecase.Rename").javaSource().contains("record Result("));
        assertTrue(api.values().get("model.usecase.access.RenameRead").javaSource().contains("Optional<Title> title()"));
        assertTrue(api.values().get("model.usecase.access.RenameAccess").javaSource().contains("Policy policy()"));
        var expected = new VernacCompiler().generate(new ProjectTypeResolver().resolve(project), org.vernac.compiler.persistence.SchemaModel.empty(), Map.of());
        for (var file : expected.generatedFiles()) {
            String name = file.packageName() + "." + file.typeSpec().name();
            if (api.values().containsKey(name)) assertEquals(file.toString(), api.values().get(name).javaSource());
        }
        assertTrue(api.values().keySet().stream().noneMatch(n -> n.contains("__Vernac")));
        assertFalse(Files.exists(root.resolve("target")));
    }

    @Test
    void invalidApplicationSignaturesDoNotHideValidServices() throws Exception {
        var api = view("""
                value Title(String);
                service Valid behavior { public Title choose(Title title) { return title; } }
                usecase Broken(Missing) behavior { execute {} }
                event Bad(Missing);
                """);
        assertTrue(api.values().containsKey("model.domain.Valid"));
        assertFalse(api.values().containsKey("model.usecase.Broken"));
        assertFalse(api.values().containsKey("model.domain.Bad"));
        assertTrue(api.valueNames().contains("model.usecase.Broken"));
    }

    @Test
    void unsavedValueHasFactoriesGettersAndPublicBehaviorWithoutWritingOutput() throws Exception {
        var api =
                view(
                        "value Count(int) behavior {public int doubled(){return"
                                + " self.intValue()*2;}};");
        String java = api.values().get("model.domain.Count").javaSource();
        assertTrue(java.contains("public static Count of(int intValue)"));
        assertTrue(java.contains("public int intValue()"));
        assertTrue(java.contains("public int doubled()"));
        assertFalse(Files.exists(root.resolve("target")));
        assertFalse(Files.exists(root.resolve("model/values.vernac")));
    }

    @Test
    void optionalGetterResultAndParameterUseTheSharedContract() throws Exception {
        String java =
                view("value Text(String? text, int number) behavior {public String?"
                         + " decorate(String? suffix){return Optional.ofNullable(suffix);}};")
                        .values()
                        .get("model.domain.Text")
                        .javaSource();
        assertTrue(java.contains("public static Text of(@Nullable String text, int number)"));
        assertTrue(java.contains("public static Text of(int number)"));
        assertTrue(java.contains("public Optional<String> text()"));
        assertTrue(java.contains("public Optional<String> decorate(@Nullable String suffix)"));
    }

    @Test
    void unrelatedSemanticAndSyntaxErrorsDoNotRemoveValidValues() throws Exception {
        var api = view("value Bad(Unknown); value Count(int); value Broken(;");
        assertEquals(Set.of("model.domain.Count"), api.values().keySet());
        assertTrue(api.valueNames().contains("model.domain.Bad"));
    }

    @Test
    void invalidValueSignaturesAreWithheldRatherThanGuessed() throws Exception {
        for (String bad :
                List.of(
                        "value Bad();",
                        "value Bad(mut int);",
                        "value Bad(int?);",
                        "value Bad(int, int);",
                        "value Bad(String toString);",
                        "value Bad(int) behavior {public Missing result(){return null;}};")) {
            var api = view("value Count(int); " + bad);
            assertTrue(api.values().containsKey("model.domain.Count"), bad);
            assertFalse(api.values().containsKey("model.domain.Bad"), bad);
        }
    }

    @Test
    void duplicateAndReservedTypeNamesAreNotPublished() throws Exception {
        assertTrue(view("value Count(int); value Count(String);").values().isEmpty());
        assertTrue(view("value String(int);").values().isEmpty());
    }

    @Test
    void currentApiMatchesGeneratedJavaExactlyAndChangesWithoutABuild() throws Exception {
        var project = model("value Count(int);");
        var resolved = new ProjectTypeResolver().resolve(project);
        String expected =
                new org.vernac.compiler.generator.ValueObjectGenerator()
                        .generate(
                                project.sources().getFirst().unit().valueObjects().getFirst(),
                                "model",
                                resolved)
                        .toString();
        assertEquals(
                expected,
                new EditorValueObjects()
                        .build(project)
                        .values()
                        .get("model.domain.Count")
                        .javaSource());
        var changed =
                view(
                        "value Count(int amount, String? label) behavior {public String"
                                + " describe(){return self.label().orElse(\"\");}};");
        String java = changed.values().get("model.domain.Count").javaSource();
        assertTrue(java.contains("public int amount()"));
        assertFalse(java.contains("public int intValue()"));
        assertTrue(java.contains("public String describe()"));
        assertEquals(Set.of("model.domain.Total"), view("value Total(int);").values().keySet());
    }

    @Test
    void referencesUseQualifiedSemanticTypesAndDoNotRecursivelyGenerateThem() throws Exception {
        var api = view("id ItemId; value Name(String); value Pair(ItemId, Name);");
        String java = api.values().get("model.domain.Pair").javaSource();
        assertTrue(java.contains("public static Pair of(ItemId itemId, Name name)"));
        assertTrue(api.values().containsKey("model.domain.ItemId"));
        assertTrue(api.liveNames().contains("model.domain.ItemId"));
    }

    @Test
    void partialResolutionCannotBePassedToTheBuildGeneratorAsAValidProject() throws Exception {
        var resolved =
                new ProjectTypeResolver()
                        .resolveForAnalysis(model("value Count(int); value Broken(Missing);"));
        assertThrows(
                org.vernac.compiler.analyzer.SemanticValidationException.class,
                () ->
                        new VernacCompiler()
                                .generate(
                                        resolved,
                                        org.vernac.compiler.persistence.SchemaModel.empty(),
                                        Map.of()));
    }

    @Test
    void dependencyValuesAreResolvedButNeverReprojected() throws Exception {
        var local = model("import shared.Name; value Label(Name);");
        var imported =
                new VernacSourceFile(
                        root.resolve("dependency-contract.vernac"),
                        new VernacSourceParser()
                                .parse("contract.vernac", "namespace shared; value Name(String);"),
                        true);
        var sources = new ArrayList<>(local.sources());
        sources.add(imported);
        var merged = new VernacProjectLoader().index(root, sources);
        var api = new EditorValueObjects().build(merged);
        assertTrue(api.values().containsKey("model.domain.Label"));
        assertFalse(api.values().containsKey("shared.domain.Name"));
    }

    @Test
    void readingBuildOwnershipDoesNotModifyGeneratedJavaOrItsInventory() throws Exception {
        Path output = root.resolve("target/generated-sources/vernac");
        new VernacCompiler().compileSource("namespace model; value Count(int);").writeTo(output);
        Path generated = output.resolve("model/domain/Count.java");
        Path manifest = output.resolve(".vernac-generated-sources");
        String java = Files.readString(generated), inventory = Files.readString(manifest);
        assertTrue(GeneratedSourceOwnership.sources(output).contains(generated));
        assertTrue(
                view("value Count(int amount);")
                        .values()
                        .get("model.domain.Count")
                        .javaSource()
                        .contains("int amount()"));
        assertEquals(java, Files.readString(generated));
        assertEquals(inventory, Files.readString(manifest));
    }

    @Test
    void currentDomainVocabularyIsIncludedInTheJavaView() throws Exception {
        var api = view("term Count {description \"Number of planned steps.\";} value Count(int);");
        assertTrue(
                api.values()
                        .get("model.domain.Count")
                        .javaSource()
                        .contains("Number of planned steps."));
    }

    @Test
    void newValidatedValueAlsoSuppliesItsActualReadInterface() throws Exception {
        var api = view("value Count(int) validates {require(self.intValue() >= 0);};");
        assertTrue(api.values().containsKey("model.domain.Count"));
        assertTrue(
                api.values()
                        .get("model.domain.access.CountRead")
                        .javaSource()
                        .contains("int intValue()"));
    }

    @Test
    void generatedViewCompilesAndItsFactoryReallyReturnsAValue() throws Exception {
        var api = view("value Count(int);");
        var compiled =
                org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(
                        Map.of(
                                "model.domain.Count",
                                api.values().get("model.domain.Count").javaSource()));
        {
            assertTrue(compiled.success(), compiled.diagnostics().toString());
            var type = compiled.classLoader().loadClass("model.domain.Count");
            Object count = type.getMethod("of", int.class).invoke(null, 7);
            assertEquals(7, type.getMethod("intValue").invoke(count));
        }
    }
    @Test
    void idsEnumsAndAllValueCollectionsMatchTheStrictGeneratorsExactly() throws Exception {
        var project = model("""
            id ItemId list ItemIds, set SelectedIds;
            value State = NEW | DONE list States;
            value Name(String) list Names, set UniqueNames;
            """);
        var api = new EditorValueObjects().build(project);
        var generated = new VernacCompiler().generate(new ProjectTypeResolver().resolve(project),
                org.vernac.compiler.persistence.SchemaModel.empty(), Map.of());
        assertEquals(Set.of("model.domain.ItemId", "model.domain.ItemIds", "model.domain.SelectedIds",
                "model.domain.State", "model.domain.States", "model.domain.Name", "model.domain.Names",
                "model.domain.UniqueNames"), api.values().keySet());
        for (var file : generated.generatedFiles()) {
            String name = file.packageName() + "." + file.typeSpec().name();
            assertEquals(file.toString(), api.values().get(name).javaSource(), name);
        }
        assertEquals(api.values().keySet(), api.valueNames());
        assertFalse(Files.exists(root.resolve("target")));
    }

    @Test
    void listAndSetViewsKeepTheirDifferentApisAndIndependentBehaviors() throws Exception {
        var api = view("""
            value Name(String) list behavior {
                public int longest(){return 0;}
            }, set ChosenNames behavior {
                public boolean accepts(Name name){return true;}
            };
            """);
        String list = api.values().get("model.domain.Names").javaSource();
        String set = api.values().get("model.domain.ChosenNames").javaSource();
        assertTrue(list.contains("public Name get(int index)"));
        assertTrue(list.contains("public Names duplicates()"));
        assertTrue(list.contains("public int longest()"));
        assertFalse(list.contains("public boolean accepts("));
        assertTrue(set.contains("public boolean accepts(Name name)"));
        assertTrue(set.contains("public Set<Name> asSet()"));
        assertFalse(set.contains("public Name get("));
        assertFalse(set.contains(" duplicates()"));
    }

    @Test
    void invalidCollectionDoesNotHideItsElementOrValidSibling() throws Exception {
        var api = view("""
            id ItemId;
            value Name(String) list Names, set BadNames behavior {
                public Missing broken(){return null;}
            };
            value State = NEW | NEW;
            """);
        assertTrue(api.values().containsKey("model.domain.ItemId"));
        assertTrue(api.values().containsKey("model.domain.Name"));
        assertTrue(api.values().containsKey("model.domain.Names"));
        assertFalse(api.values().containsKey("model.domain.BadNames"));
        assertFalse(api.values().containsKey("model.domain.State"));
        assertTrue(api.valueNames().contains("model.domain.BadNames"));
    }

    @Test
    void invalidOwnerWithholdsItsCollectionsAndDuplicateCollectionNamesAreRejected() throws Exception {
        var api = view("value Bad(Missing) list Bads; value Good(String) list Same, set Same;");
        assertFalse(api.values().containsKey("model.domain.Bad"));
        assertFalse(api.values().containsKey("model.domain.Bads"));
        assertFalse(api.values().containsKey("model.domain.Same"));
        assertTrue(api.values().containsKey("model.domain.Good"));
    }

    @Test
    void enumMethodSignaturesUseTheSameBehaviorContract() throws Exception {
        var api = view("value State = NEW | DONE behavior {public String label(){return self.name();}};");
        String java = api.values().get("model.domain.State").javaSource();
        assertTrue(java.contains("public enum State"));
        assertTrue(java.contains("NEW"));
        assertTrue(java.contains("public String label()"));
    }

    @Test
    void entityCollectionsAreProjectedAndRenamedValueCollectionsDisappear() throws Exception {
        var api = view("id ItemId; entity Item[ItemId]() list Items; value Name(String) list Names;");
        assertTrue(api.values().containsKey("model.domain.Item"));
        assertTrue(api.values().containsKey("model.domain.Items"));
        assertTrue(api.liveNames().contains("model.domain.Items"));
        var changed = view("value Name(String) set SelectedNames;");
        assertFalse(changed.values().containsKey("model.domain.Names"));
        assertTrue(changed.values().containsKey("model.domain.SelectedNames"));
    }

    @Test
    void valueSideApisCompileTogetherAndRetainTheirActualRuntimeSemantics() throws Exception {
        var api = view("id ItemId list ItemIds; value State = NEW | DONE set States; value Name(String) list Names;");
        Map<String, String> sources = new HashMap<>();
        api.values().forEach((name, value) -> sources.put(name, value.javaSource()));
        var compiled = org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(sources);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        var id = compiled.classLoader().loadClass("model.domain.ItemId");
        var created = id.getMethod("create").invoke(null);
        assertNotNull(id.getMethod("asString").invoke(created));
        var ids = compiled.classLoader().loadClass("model.domain.ItemIds");
        var empty = ids.getMethod("empty").invoke(null);
        var added = ids.getMethod("plus", id).invoke(empty, created);
        assertEquals(0, ids.getMethod("size").invoke(empty));
        assertEquals(1, ids.getMethod("size").invoke(added));
        var state = compiled.classLoader().loadClass("model.domain.State");
        assertEquals("NEW", state.getMethod("name").invoke(state.getEnumConstants()[0]));
    }


    @Test
    void mutableFactoriesAccessViewsAndCollectionsMatchTheProductionGenerators() throws Exception {
        var project = model("""
            id LineId; id OrderId;
            value Label(String);
            entity Line[LineId](Label label, mut Label? note) behavior {
                read Label describe(){return self.label();}
                modify void annotate(Label? note){self.note(note);}
            } list Lines, set UniqueLines;
            aggregate Order[OrderId](Label, mut Lines, mut Line? preferred) behavior {
                read Lines planned(){return self.lines();}
                modify void replace(Lines lines){self.lines(lines);}
            } list Orders, set SelectedOrders;
            """);
        var api = new EditorValueObjects().build(project);
        var generated = new VernacCompiler().generate(new ProjectTypeResolver().resolve(project),
                org.vernac.compiler.persistence.SchemaModel.empty(), Map.of());
        Map<String, String> actual = new HashMap<>();
        generated.generatedFiles().forEach(f -> actual.put(f.packageName()+"."+f.typeSpec().name(), f.toString()));
        api.values().forEach((name, value) -> assertEquals(actual.get(name), value.javaSource(), name));
        assertEquals(Set.of("model.domain.LineId", "model.domain.OrderId", "model.domain.Label",
            "model.domain.Line", "model.domain.Lines", "model.domain.UniqueLines", "model.domain.Order",
            "model.domain.Orders", "model.domain.SelectedOrders"), api.valueNames());
        assertEquals(api.valueNames().size() + 6, api.values().size());
        String line = api.values().get("model.domain.Line").javaSource();
        assertTrue(line.contains("public static Line create(Label label)"));
        assertTrue(line.contains("public static Line create(Label label, @Nullable Label note)"));
        assertTrue(line.contains("public static Line reconstitute(LineId id, Label label, @Nullable Label note)"));
        assertTrue(line.contains("public Optional<Label> note()"));
        String read = api.values().get("model.domain.access.LineRead").javaSource();
        String write = api.values().get("model.domain.access.LineWrite").javaSource();
        String access = api.values().get("model.domain.access.LineAccess").javaSource();
        assertTrue(read.contains("Label describe()"));
        assertFalse(read.contains("annotate("));
        assertFalse(read.contains("void note("));
        assertFalse(read.contains("emit("));
        assertTrue(write.contains("void annotate(@Nullable Label note)"));
        assertTrue(write.contains("void note(@Nullable Label note)"));
        assertTrue(write.contains("void emit(DomainEvent event)"));
        assertTrue(access.contains("extends LineRead, LineWrite"));
        String orderRead = api.values().get("model.domain.access.OrderRead").javaSource();
        assertTrue(orderRead.contains("Instant createdAt()"));
        assertTrue(orderRead.contains("Instant updatedAt()"));
        assertTrue(api.values().get("model.domain.Lines").javaSource().contains("by(LineId id)"));
        assertTrue(api.values().get("model.domain.SelectedOrders").javaSource().contains("contains(OrderId id)"));
        assertFalse(Files.exists(root.resolve("target")));
    }

    @Test
    void invalidMutableSignaturesWithholdTheirAccessFamilyAndCollections() throws Exception {
        for (String declaration : List.of(
            "entity Bad[MissingId]() list Bads;",
            "entity Bad[ValidId](Missing field) list Bads;",
            "entity Bad[ValidId](int number) list Bads;",
            "entity Bad[ValidId]() behavior {public void legacy(){}} list Bads;",
            "aggregate Bad[ValidId](Other child) list Bads;")) {
            var api = view("id ValidId; value Label(String); aggregate Other[ValidId](); " + declaration);
            for (String name : List.of("model.domain.Bad", "model.domain.Bads", "model.domain.access.BadRead",
                    "model.domain.access.BadWrite", "model.domain.access.BadAccess"))
                assertFalse(api.values().containsKey(name), declaration+" -> "+name);
            assertTrue(api.values().containsKey("model.domain.Label"));
        }
    }

    @Test
    void badEntityCollectionDoesNotRemoveItsOwnerAccessViewsOrSiblingCollection() throws Exception {
        var api = view("""
            id LineId;
            entity Line[LineId]() list Lines, set Broken behavior {
                public Missing rejected(){return null;}
            };
            """);
        assertTrue(api.values().containsKey("model.domain.Line"));
        assertTrue(api.values().containsKey("model.domain.access.LineAccess"));
        assertTrue(api.values().containsKey("model.domain.Lines"));
        assertFalse(api.values().containsKey("model.domain.Broken"));
    }

    @Test
    void readAndAccessViewsRemainAvailableWhileJavaBodyTextIsUnfinished() throws Exception {
        var api = view("""
            id LineId; value Label(String);
            entity Line[LineId](mut Label) behavior {
                read Label describe(){self.}
                modify void rename(Label label){self.}
            };
            value Broken(Missing);
            """);
        assertTrue(api.values().containsKey("model.domain.Line"));
        assertTrue(api.values().get("model.domain.access.LineRead").javaSource().contains("Label label()"));
        assertTrue(api.values().get("model.domain.access.LineWrite").javaSource().contains("void label(Label label)"));
    }

    @Test
    void mutableApiCompilesWithItsActualDelegatesAndExecutesTheSharedBehaviorContract() throws Exception {
        var project = model("""
            id LineId; id OrderId; value Label(String);
            entity Line[LineId](mut Label) behavior {
                read Label describe(){return self.label();}
                modify void rename(Label label){self.label(label);}
            } list Lines;
            aggregate Order[OrderId](mut Lines) behavior {
                modify void add(Line line){self.lines(self.lines().plus(line));}
            };
            """);
        var api = new EditorValueObjects().build(project);
        Map<String, String> sources = new HashMap<>();
        api.values().forEach((name, value) -> sources.put(name, value.javaSource()));
        // Only implementation companions are added from the real strict build; no signature stubs.
        var compiledProject = new VernacCompiler().generate(new ProjectTypeResolver().resolve(project),
                org.vernac.compiler.persistence.SchemaModel.empty(), Map.of());
        compiledProject.generatedFiles().forEach(f -> sources.putIfAbsent(f.packageName()+"."+f.typeSpec().name(), f.toString()));
        var compiled = org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(sources);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        var loader = compiled.classLoader();
        var labelType = loader.loadClass("model.domain.Label");
        var lineType = loader.loadClass("model.domain.Line");
        var linesType = loader.loadClass("model.domain.Lines");
        var orderType = loader.loadClass("model.domain.Order");
        var before = labelType.getMethod("of", String.class).invoke(null, "Before");
        var after = labelType.getMethod("of", String.class).invoke(null, "After");
        assertThrows(NoSuchMethodException.class, () -> lineType.getMethod("label", labelType));
        var line = lineType.getMethod("create", labelType).invoke(null, before);
        var order = orderType.getMethod("create", linesType).invoke(null, linesType.getMethod("empty").invoke(null));
        orderType.getMethod("add", lineType).invoke(order, line);
        lineType.getMethod("rename", labelType).invoke(line, after);
        var lines = orderType.getMethod("lines").invoke(order);
        assertSame(line, linesType.getMethod("get", int.class).invoke(lines, 0));
        assertSame(after, lineType.getMethod("describe").invoke(line));
        assertFalse(loader.loadClass("model.domain.access.LineAccess").isInstance(line));
    }

    @Test
    void externalMutableTypesResolveWithoutReprojectingTheirImplementationOrAccessViews() throws Exception {
        var local = model("import shared.Child; id OwnerId; aggregate Owner[OwnerId](Child);");
        var external = new VernacSourceFile(root.resolve("external-contract.vernac"),
            new VernacSourceParser().parse("external-contract.vernac",
                "namespace shared; id ChildId; value Name(String); entity Child[ChildId](Name) list Children;"), true);
        var sources = new ArrayList<>(local.sources());
        sources.add(external);
        var api = new EditorValueObjects().build(new VernacProjectLoader().index(root, sources));
        assertTrue(api.values().containsKey("model.domain.Owner"));
        assertTrue(api.values().containsKey("model.domain.access.OwnerAccess"));
        assertTrue(api.values().keySet().stream().noneMatch(n -> n.startsWith("shared.")));
    }
}
