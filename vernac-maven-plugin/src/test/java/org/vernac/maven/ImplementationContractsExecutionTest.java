// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.tooling.VernacTypeReferences;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.events.EventHandler;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

import javax.tools.*;

/** Real generated API/implementation calls, rather than annotations alone. */
class ImplementationContractsExecutionTest {
    @TempDir Path directory;
    private final List<URLClassLoader> loaders = new ArrayList<>();

    @AfterEach
    void closeLoaders() throws Exception {
        for (var loader : loaders) loader.close();
    }

    private record Compiled(
            URLClassLoader loader,
            List<Diagnostic<? extends JavaFileObject>> diagnostics,
            boolean success) {
        Class<?> loadClass(String name) throws ClassNotFoundException {
            return loader.loadClass(name);
        }
    }

    private Compiled compile(Map<String, String> sources) {
        try {
            Path root = Files.createDirectories(directory.resolve(UUID.randomUUID().toString()));
            Path classes = Files.createDirectories(root.resolve("classes"));
            List<Path> paths = new ArrayList<>();
            for (var entry : sources.entrySet()) {
                Path path = root.resolve(entry.getKey().replace('.', '/') + ".java");
                Files.createDirectories(path.getParent());
                Files.writeString(path, entry.getValue());
                paths.add(path);
            }
            var compiler = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            boolean success;
            try (var manager =
                    compiler.getStandardFileManager(
                            diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
                success =
                        compiler.getTask(
                                        null,
                                        manager,
                                        diagnostics,
                                        List.of(
                                                "--release",
                                                "21",
                                                "-proc:none",
                                                "-classpath",
                                                System.getProperty("java.class.path"),
                                                "-d",
                                                classes.toString()),
                                        null,
                                        manager.getJavaFileObjectsFromPaths(paths))
                                .call();
            }
            var loader =
                    new URLClassLoader(
                            new java.net.URL[] {classes.toUri().toURL()},
                            getClass().getClassLoader());
            loaders.add(loader);
            return new Compiled(loader, diagnostics.getDiagnostics(), success);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final String MODEL =
            """
            namespace unified;
            id ItemId;
            value Title(String) behavior {
                java imports { external.Shared; }
                public String? text(String? candidate) implemented by Shared;
                public String required(String text) {return text;}
            } list Titles behavior {public String required(String text) {return text;}};
            value Alias(String) behavior {public String text() implemented by Defaults;};
            value Flag = A | B behavior {public String required(String text) {return text;}};
            entity Item[ItemId](Title) behavior {read String required(String text) {return text;}};
            aggregate Root[ItemId](mut Title) behavior {modify void replace(Title title) {self.title(title);}};
            service Planning behavior {
                java imports {external.Shared;}
                public Title? maybe(Title? title) implemented by Shared;
                public Title required(Title title) {return title;}
                public Title? through(Title? title) {return identity(title);}
                private Title? identity(Title? title) {return Optional.ofNullable(title);}
            }
            port Archive {Title? maybe(Title? title); Title required(Title title);}
            adapter Local implements Archive {behavior {
                java imports {external.Shared;}
                public Title? maybe(Title? title) implemented by Shared;
                public Title required(Title title) {return title;}
            }}
            usecase External(Title? title) returns Title? behavior {
                java imports {external.Shared;}
                execute implemented by Shared;
            }
            usecase Inline(Title? title) returns Title? behavior {
                execute {return identity(title);}
                private Title? identity(Title? title) {return Optional.ofNullable(title);}
            }
            event Changed(Title?);
            synchronous listener Observe on Changed behavior {
                java imports {external.Shared;}
                execute implemented by Shared;
            }
            """;

    private Compiled fixture() {
        var result = new VernacCompiler().compileSource(MODEL);
        Map<String, String> sources = new LinkedHashMap<>();
        result.generatedFiles()
                .forEach(
                        f ->
                                sources.put(
                                        f.packageName() + "." + f.typeSpec().name(), f.toString()));
        sources.put(
                "external.Shared",
                """
                package external;
                import java.util.Optional;
                import org.jspecify.annotations.*;
                import unified.domain.*;
                @NullMarked public final class Shared {
                    public static Optional<String> text(Title self,@Nullable String candidate) {return Optional.ofNullable(candidate);}
                    public static Optional<Title> maybe(unified.domain.access.PlanningAccess self,@Nullable Title title) {return Optional.ofNullable(title);}
                    public static Optional<Title> maybe(unified.adapter.outbound.access.LocalAccess self,@Nullable Title title) {return Optional.ofNullable(title);}
                    public static Optional<Title> execute(unified.usecase.access.ExternalAccess self,@Nullable Title title) {return Optional.ofNullable(title);}
                    public static void execute(unified.adapter.inbound.event.access.ObserveAccess self,Changed event) {}
                }
                """);
        sources.put(
                "unified.domain.Defaults",
                """
                package unified.domain;
                public final class Defaults {public static String text(Alias self) {return self.string();}}
                """);
        var output = compile(sources);
        assertTrue(output.success(), output.diagnostics().toString());
        return output;
    }

    @Test
    void nullableInputsWorkThroughInlineHelpersExternalImportsAndPackageFallback()
            throws Exception {
        var code = fixture();
        var title = code.loadClass("unified.domain.Title");
        Object value = title.getMethod("of", String.class).invoke(null, "hello");
        assertEquals(
                Optional.empty(),
                title.getMethod("text", String.class).invoke(value, new Object[] {null}));
        assertEquals(
                Optional.of("candidate"),
                title.getMethod("text", String.class).invoke(value, "candidate"));
        for (String name : List.of("unified.domain.Planning", "unified.adapter.outbound.Local")) {
            var type = code.loadClass(name);
            Object bean = type.getConstructor().newInstance();
            assertEquals(
                    Optional.empty(),
                    type.getMethod("maybe", title).invoke(bean, new Object[] {null}));
            assertEquals(Optional.of(value), type.getMethod("maybe", title).invoke(bean, value));
        }
        var planning = code.loadClass("unified.domain.Planning");
        assertEquals(
                Optional.empty(),
                planning.getMethod("through", title)
                        .invoke(planning.getConstructor().newInstance(), new Object[] {null}));
        var provider = new DefaultListableBeanFactory().getBeanProvider(EventHandler.class);
        for (String name : List.of("External", "Inline")) {
            var type = code.loadClass("unified.usecase." + name);
            Object bean = type.getConstructors()[0].newInstance(provider);
            assertEquals(
                    Optional.empty(),
                    type.getMethod("execute", title).invoke(bean, new Object[] {null}));
            assertEquals(Optional.of(value), type.getMethod("execute", title).invoke(bean, value));
        }
        var alias = code.loadClass("unified.domain.Alias");
        assertEquals(
                "fallback",
                alias.getMethod("text")
                        .invoke(alias.getMethod("of", String.class).invoke(null, "fallback")));
    }

    @Test
    void requiredBehaviorInputsFailWithDomainValidationAcrossOwnersBeforeTheirBody()
            throws Exception {
        var code = fixture();
        var title = code.loadClass("unified.domain.Title");
        Object value = title.getMethod("of", String.class).invoke(null, "hello");
        List<Object> targets = new ArrayList<>();
        targets.add(value);
        var titles = code.loadClass("unified.domain.Titles");
        targets.add(titles.getMethod("of", Iterable.class).invoke(null, List.of(value)));
        targets.add(code.loadClass("unified.domain.Flag").getEnumConstants()[0]);
        var item = code.loadClass("unified.domain.Item");
        targets.add(item.getMethod("create", title).invoke(null, value));
        for (Object target : targets) {
            var error =
                    assertThrows(
                            InvocationTargetException.class,
                            () ->
                                    target.getClass()
                                            .getMethod("required", String.class)
                                            .invoke(target, new Object[] {null}));
            assertInstanceOf(DomainValidationException.class, error.getCause());
        }
        for (String name : List.of("unified.domain.Planning", "unified.adapter.outbound.Local")) {
            var type = code.loadClass(name);
            Object target = type.getConstructor().newInstance();
            var error =
                    assertThrows(
                            InvocationTargetException.class,
                            () ->
                                    type.getMethod("required", title)
                                            .invoke(target, new Object[] {null}));
            assertInstanceOf(DomainValidationException.class, error.getCause());
        }
        var root = code.loadClass("unified.domain.Root");
        Object target = root.getMethod("create", title).invoke(null, value);
        var error =
                assertThrows(
                        InvocationTargetException.class,
                        () -> root.getMethod("replace", title).invoke(target, new Object[] {null}));
        assertInstanceOf(DomainValidationException.class, error.getCause());
        assertSame(value, root.getMethod("title").invoke(target));
    }

    @Test
    void nullableOverloadsUseTheirRawJavaTypesRatherThanOptionalErasure() {
        var result =
                new VernacCompiler()
                        .compileSource(
                                """
                                namespace overloaded; value Title(String); value Reason(String);
                                service Overloads behavior {public void helper(Title? value) {} public void helper(Reason? value) {}}
                                usecase Inspect() behavior {
                                    execute {helper((Title)null);helper((Reason)null);}
                                    private void helper(Title? value) {}
                                    private void helper(Reason? value) {}
                                }
                                """);
        var code =
                compile(
                        result.generatedFiles().stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                f -> f.packageName() + "." + f.typeSpec().name(),
                                                Object::toString)));
        assertTrue(code.success(), code.diagnostics().toString());
    }

    @Test
    void nullabilityOnlyAndReturnOnlyOverloadsHaveEarlyVernacDiagnostics() {
        for (String declaration :
                List.of(
                        "usecase Bad() behavior {execute {} private void helper(Title value) {}"
                            + " private void helper(Title? value) {}}",
                        "service Bad behavior {public void helper(Title value) {} public void"
                            + " helper(Title? value) {}}",
                        "value Bad(String) behavior {public String helper(Title value) {return"
                            + " \"\";} public int helper(Title value) {return 1;}}")) {
            var error =
                    assertThrows(
                            SemanticValidationException.class,
                            () ->
                                    new VernacCompiler()
                                            .compileSource(
                                                    "namespace collision; value Title(String);"
                                                            + declaration));
            assertTrue(error.getMessage().toLowerCase().contains("signature"), error.getMessage());
        }
    }

    @Test
    void shortNamesCannotBypassArchitectureChecksOrChooseAmbiguousImports() {
        for (String declaration :
                List.of(
                        "value Bad(String) behavior {public String text() implemented by Store;};",
                        "value Bad(String) behavior {java imports {first.Impl;second.Impl;} public"
                            + " String text() implemented by Impl;};")) {
            var error =
                    assertThrows(
                            SemanticValidationException.class,
                            () ->
                                    new VernacCompiler()
                                            .compileSource(
                                                    "namespace collision; id RootId; aggregate"
                                                        + " Root[RootId](); repository Store for"
                                                        + " Root {}"
                                                            + declaration));
            assertTrue(
                    error.getMessage().contains("repository")
                            || error.getMessage().contains("Ambiguous Java implementation"),
                    error.getMessage());
        }
    }

    @Test
    void mappingAndInboundErrorShortTargetsCompileInTheirActualGeneratedPackages() {
        var result =
                new VernacCompiler()
                        .compileSource(
                                """
                                namespace web;
                                value Title(String);
                                usecase Read() returns Title behavior {execute {return Title.of("hello");}}
                                adapter Json inbound http uses Read reader {
                                    base "/api";
                                    schema Payload(String text);
                                    mapping toPayload: Payload <- Title implemented by Converter;
                                    get {GET "/";execute reader {} respond 200 Payload using toPayload;}
                                    errors implemented by ProblemPolicy;
                                }
                                adapter Page inbound mvc uses Read reader {
                                    base "/";error view "problem";
                                    get {GET "/";execute reader {} render "page" model title;}
                                    errors implemented by ProblemPolicy;
                                }
                                """);
        Map<String, String> sources = new LinkedHashMap<>();
        result.generatedFiles()
                .forEach(
                        f ->
                                sources.put(
                                        f.packageName() + "." + f.typeSpec().name(), f.toString()));
        sources.put(
                "web.adapter.inbound.http.mapping.Converter",
                """
                package web.adapter.inbound.http.mapping;
                public final class Converter {
                    public static web.adapter.inbound.http.dto.JsonDtos.Payload toPayload(web.domain.Title source) {
                        return web.adapter.inbound.http.dto.JsonDtos.Payload.of(source.string());
                    }
                }
                """);
        sources.put(
                "web.adapter.inbound.http.ProblemPolicy",
                """
                package web.adapter.inbound.http;
                public final class ProblemPolicy {
                    public static org.vernac.runtime.http.inbound.InboundHttpErrors.Reply handle(String endpoint, Exception error) {
                        return org.vernac.runtime.http.inbound.InboundHttpErrors.defaultReply(error);
                    }
                }
                """);
        var code = compile(sources);
        assertTrue(code.success(), code.diagnostics().toString());
    }

    @Test
    void nativeJavaTargetsFollowTheSameImportsAndGeneratedPackageFallback() {
        var refs = new VernacTypeReferences().javaReferences(MODEL);
        assertTrue(refs.stream().anyMatch(r -> r.javaName().equals("external.Shared")));
        assertTrue(refs.stream().anyMatch(r -> r.javaName().equals("unified.domain.Defaults")));
        assertFalse(
                refs.stream()
                        .anyMatch(
                                r ->
                                        r.javaName().equals("Shared")
                                                || r.javaName().equals("Defaults")));
    }
}
