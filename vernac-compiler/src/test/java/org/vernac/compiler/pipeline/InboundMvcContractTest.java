// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.modules.*;

import java.nio.file.*;
import java.util.*;

class InboundMvcContractTest {
    @TempDir Path temporary;
    private final VernacCompiler compiler = new VernacCompiler();
    private static final String MODEL =
            """
            namespace example;
            id ItemId; value Text(String); value Reason = ALREADY_DONE;
            usecase Read() returns Text behavior { execute { return Text.of("page"); } }
            usecase Write(Text) returns ItemId behavior { execute { return ItemId.create(); } }
            usecase Nothing() behavior { execute {} }
            """;

    private String adapter(String endpoints) {
        return MODEL
                + "adapter Web inbound mvc uses Read read, Write write, Nothing nothing {base"
                + " \"/\";error view \"problem\";"
                + endpoints
                + "}";
    }

    private static final String BOARD =
            "board {GET \"/\";execute read {} render \"board\" model page;}";

    @Test
    void generatesViewsFormsRedirectsAndTypedSharedErrorSelectors() {
        var result =
                compiler.compileSource(
                        adapter(
                                BOARD
                                        + """
                                        create {
                                            POST "/items";
                                            form String title;
                                            sensitive form String? password;
                                            form boolean accepted default "false";
                                            execute write { text <- form.title; }
                                            redirect "/" message "Saved.";
                                            errors {
                                                when validation then redisplay board status 422 "BAD_TITLE" "Check the title.";
                                                when rejection Reason.ALREADY_DONE then render "conflict" status 409;
                                                when integration remote TIMEOUT then render "problem" status 503 "DEPENDENCY_DOWN" "Try later.";
                                            }
                                        }
                                        """));
        String java =
                result.generatedFiles().stream().map(Object::toString).reduce("", String::concat);
        assertTrue(java.contains("@Controller"));
        assertFalse(java.contains("@RestController"));
        assertTrue(java.contains("Text.of(__wire0)"));
        assertTrue(java.contains("inputs.required(\"form.accepted\", \"false\")"));
        assertTrue(
                java.contains("new MvcInputs.Binding(\"form\", \"password\", \"password\", true)"));
        assertTrue(java.contains("MvcResponses.redisplay(() -> __board(inputs.forLoader("));
        assertTrue(java.contains("InboundHttpErrors.rejection(error, Reason.ALREADY_DONE)"));
        assertTrue(java.contains("IntegrationCategory.TIMEOUT"));
        assertFalse(
                result.generatedFiles().stream()
                        .filter(f -> f.typeSpec().name().equals("Web"))
                        .findFirst()
                        .orElseThrow()
                        .toString()
                        .contains("@Transactional"));
    }

    @Test
    void rejectsIncorrectViewsRepliesDefaultsAndReloadContractsWithVernacDiagnostics() {
        for (var text :
                List.of(
                        adapter("x {GET \"/\";execute read {} render \"board\";}"),
                        adapter("x {GET \"/\";execute nothing {} render \"board\" model page;}"),
                        adapter("x {GET \"/\";execute read {} render \"../board\" model page;}"),
                        adapter(
                                "x {GET \"/\";execute read {} render \"redirect:/elsewhere\" model"
                                        + " page;}"),
                        adapter("x {GET \"/\";execute read {} render \"board\" model inputs;}"),
                        adapter("x {GET \"/\";execute nothing {} redirect \"//evil.example\";}"),
                        adapter(
                                "x {GET \"/\";execute nothing {} redirect"
                                        + " \"https://evil.example\";}"),
                        adapter(
                                "x {GET \"/\";form String title;execute write {text <- form.title;}"
                                        + " redirect \"/\";}"),
                        adapter(
                                "x {GET \"/\";query int page default \"one\";execute read {} render"
                                        + " \"board\" model page;}"),
                        adapter(
                                "x {GET \"/\";query int? page;execute read {} render \"board\""
                                        + " model page;}"),
                        adapter(
                                "x {POST \"/\";form String csrf named \"_csrf\";execute nothing {}"
                                        + " redirect \"/\";}"),
                        adapter(
                                "x {POST \"/\";query String x;form String y named \"x\";execute"
                                        + " nothing {} redirect \"/\";}"),
                        adapter(
                                "x {POST \"/\";execute nothing {} redirect \"/\";errors {when"
                                        + " validation then redisplay missing status 422;}}"),
                        adapter(
                                "x {GET \"/\";execute nothing {} render \"page\"; errors {when"
                                        + " validation then redisplay x status 422;}}"),
                        adapter(
                                "x {GET \"/{id}\";path UUID id;execute read {} render \"board\""
                                        + " model page;} y {POST \"/\";execute nothing {} redirect"
                                        + " \"/\";errors {when validation then redisplay x status"
                                        + " 422;}}"),
                        adapter(
                                BOARD
                                        + "errors {when validation then redisplay board status"
                                        + " 422;}"),
                        adapter(BOARD).replace("error view \"problem\";", ""),
                        adapter("x {GET \"/\";execute nothing {} respond 204;}"),
                        adapter(BOARD).replace("inbound mvc", "inbound http"))) {
            var error =
                    assertThrows(
                            SemanticValidationException.class,
                            () -> compiler.compileSource(text),
                            text);
            assertFalse(error.getMessage().contains("Syntax error"), error.getMessage());
        }
    }

    @Test
    void redisplayCannotSatisfyARequiredLoaderFromAnOptionalOrDifferentWireType() {
        for (String binding : List.of("query Integer? page;", "query String page;")) {
            var error =
                    assertThrows(
                            SemanticValidationException.class,
                            () ->
                                    compiler.compileSource(
                                            adapter(
                                                    "board {GET \"/\";query Integer page;execute"
                                                        + " read {} render \"board\" model"
                                                        + " page;}save {POST \"/\";"
                                                            + binding
                                                            + "execute nothing {} redirect"
                                                            + " \"/\";errors {when validation then"
                                                            + " redisplay board status 422;}}")));
            assertTrue(
                    error.getMessage().contains("requires unavailable binding query.page"),
                    error.getMessage());
        }
    }

    @Test
    void optionalResultsRequireExplicitAbsentViewsAndRoutesCollideWithJson() {
        String optional =
                MODEL
                        + "usecase Maybe() returns Text? behavior {execute {return"
                        + " java.util.Optional.empty();}}";
        String endpoint =
                "adapter Web inbound mvc uses Maybe maybe {base \"/\";error view \"problem\"; x"
                        + " {GET \"/\";execute maybe {} render \"page\" model result;";
        assertThrows(
                SemanticValidationException.class,
                () -> compiler.compileSource(optional + endpoint + "}}"));
        assertDoesNotThrow(
                () ->
                        compiler.compileSource(
                                optional
                                        + endpoint
                                        + "on absent render \"missing\" status 404;}}"));
        var error =
                assertThrows(
                        SemanticValidationException.class,
                        () ->
                                compiler.compileSource(
                                        adapter(BOARD)
                                                + "adapter Json inbound http uses Nothing nothing"
                                                + " {base \"/\";x {GET \"/\";execute nothing {}"
                                                + " respond 204;}}"));
        assertTrue(error.getMessage().contains("Duplicate HTTP route"));
    }

    @Test
    void moduleActivationAndDebuggerMetadataUseTheActualGeneratedMvcClass() throws Exception {
        Path source = temporary.resolve("example/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                adapter(
                        BOARD
                                + "create {POST \"/items\";form String title;execute write {text <-"
                                + " form.title;}redirect \"/\";}"));
        var resolved = compiler.analyzeProject(temporary);
        var result = compiler.compileProject(temporary);
        assertTrue(
                result.debugMap().entries().stream()
                        .anyMatch(
                                e ->
                                        e.javaClass().equals("example.adapter.inbound.http.Web")
                                                && e.method().equals("__create")));
        var module =
                ModuleDefinition.parse(
                        temporary.resolve("vernac-module.vmodule"),
                        "module example.web { export example.Text; }");
        var descriptor =
                ModuleContracts.publish(
                        module,
                        resolved,
                        result.generatedFiles(),
                        "example",
                        "web",
                        "1",
                        List.of());
        assertTrue(
                descriptor.components().stream()
                        .anyMatch(
                                c ->
                                        c.kind().equals("INBOUND_HTTP_ADAPTER")
                                                && c.javaName()
                                                        .equals(
                                                                "example.adapter.inbound.http.Web")));
        assertTrue(
                descriptor.components().stream()
                        .anyMatch(
                                c ->
                                        c.kind().equals("INBOUND_HTTP_ADVICE")
                                                && c.javaName()
                                                        .equals(
                                                                "example.adapter.inbound.http.WebErrors")));
    }
}
