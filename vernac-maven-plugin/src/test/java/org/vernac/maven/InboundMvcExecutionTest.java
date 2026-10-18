// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.runtime.events.EventHandler;
import org.vernac.runtime.http.inbound.InboundHttpErrors;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

import javax.tools.ToolProvider;

/**
 * Real generated controllers/usecases and Spring MVC. The starter suite separately renders
 * Thymeleaf and checks CSRF.
 */
class InboundMvcExecutionTest {
    @TempDir Path temporary;
    private static final String MODEL =
            """
            namespace mvc;
            id ItemId;
            value Text(String) validates {require(!self.string().isBlank(), "PRIVATE_TITLE_DETAIL");};
            value Index(int) validates {require(self.intValue() > 0, "PRIVATE_PAGE_DETAIL");};
            value Reason = CLOSED;
            value OtherReason = CLOSED;
            usecase Read(Index) returns Text behavior {
                java imports { fixture.Probe; }
                execute {return Probe.read(index.intValue());}
            }
            usecase Submit(Text text, Text? password, Text? note) behavior {
                java imports { fixture.Probe; }
                execute {Probe.submit(text.string());}
            }
            usecase Maybe() returns Text? behavior {execute {return java.util.Optional.empty();}}
            adapter Page inbound mvc uses Read reader, Submit submitter, Maybe maybe {
                base "/";
                error view "problem";
                board {
                    GET "/"; query int page default "1"; header String? authorization named "Authorization";
                    execute reader {index <- query.page;}
                    render "board" model page;
                }
                submit {
                    POST "/submit";
                    form String title; sensitive form String? password; form String? note;
                    form boolean accepted default "false";
                    header String? authorization named "AUTHORIZATION";
                    execute submitter {text <- form.title; password <- form.password; note <- form.note;}
                    redirect "/" message "Saved.";
                    errors {
                        when invalid request then redisplay board status 400 "FORM_INPUT" "Check the submitted fields.";
                        when validation then redisplay board status 422 "FORM_VALIDATION" "Choose valid values.";
                        when rejection Reason.CLOSED then render "conflict" status 409 "ALREADY_CLOSED" "Already closed.";
                        when integration remote TIMEOUT then render "problem" status 503 "DEPENDENCY_DOWN" "Try later.";
                    }
                }
                customPolicy {
                    POST "/custom"; form String title;
                    execute submitter {text <- form.title;}
                    redirect "/";
                    errors implemented by fixture.Policy;
                }
                optional {GET "/maybe";execute maybe {} render "detail" model detail;on absent render "missing" status 404;}
                errors {
                    when validation then render "problem" status 409 "ADAPTER_VALIDATION" "Adapter-level validation.";
                    when not found then render "problem" status 404;
                    when optimistic conflict then render "conflict" status 409;
                }
            }
            """;
    private static final String PROBE =
            """
            package fixture;
            public final class Probe {
                public static int loads, submits;
                public static void main(String[] args) {
                    var events = new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(org.vernac.runtime.events.EventHandler.class);
                    var controller = new mvc.adapter.inbound.http.Page(new mvc.usecase.Read(events),
                        new mvc.usecase.Submit(events), new mvc.usecase.Maybe(events));
                    var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/submit");
                    request.addParameter("title", "parcel");
                    request.setAttribute(org.springframework.web.servlet.DispatcherServlet.OUTPUT_FLASH_MAP_ATTRIBUTE,
                        new org.springframework.web.servlet.FlashMap());
                    controller.submit(request);
                }
                public static boolean failLoad, failPolicy;
                public static mvc.domain.Text read(int index) {
                    loads++;
                    if (failLoad) throw new IllegalStateException("PRIVATE_LOAD_DETAIL");
                    return mvc.domain.Text.of("Fresh page " + index);
                }
                public static void submit(String value) {
                    submits++;
                    var context = org.vernac.runtime.ports.AdapterInvocation.create("Payments", "Remote", "charge");
                    switch (value) {
                        case "invalid" -> throw new org.vernac.runtime.DomainValidationException("PRIVATE_DOMAIN_DETAIL");
                        case "bug" -> throw new IllegalStateException("PRIVATE_INTERNAL_DETAIL");
                        case "conflict" -> throw new org.springframework.dao.OptimisticLockingFailureException("PRIVATE_LOCK_DETAIL");
                        case "missing" -> throw new org.vernac.runtime.AggregateNotFoundException(mvc.domain.ItemId.class, "PRIVATE_ID");
                        case "reject" -> throw new org.vernac.runtime.ports.PortRejectedException(context, mvc.domain.Reason.CLOSED, "PRIVATE_REJECTION_DETAIL");
                        case "other-reject" -> throw new org.vernac.runtime.ports.PortRejectedException(context, mvc.domain.OtherReason.CLOSED, "PRIVATE_REJECTION_DETAIL");
                        case "timeout" -> throw new org.vernac.runtime.ports.RemoteIntegrationException(context, org.vernac.runtime.ports.IntegrationCategory.TIMEOUT, "PRIVATE_REMOTE_DETAIL");
                        default -> {}
                    }
                }
            }
            """;
    private static final String POLICY =
            """
            package fixture;
            public final class Policy {
                public static org.vernac.runtime.http.inbound.InboundHttpErrors.Reply handle(String endpoint, Exception error) {
                    if (Probe.failPolicy) throw new IllegalStateException("PRIVATE_POLICY_DETAIL");
                    return new org.vernac.runtime.http.inbound.InboundHttpErrors.Reply(409,"CUSTOM_CONFLICT","Custom public message.");
                }
            }
            """;

    private Fixture fixture() throws Exception {
        Path source = temporary.resolve("src/main/vernac/mvc/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(source, MODEL);
        var result = new VernacCompiler().compileProject(temporary.resolve("src/main/vernac"));
        Path classes = temporary.resolve("classes"), java = temporary.resolve("java");
        Files.createDirectories(classes);
        List<String> sources = new ArrayList<>();
        for (var file : result.generatedFiles()) {
            Path path =
                    java.resolve(file.packageName().replace('.', '/'))
                            .resolve(file.typeSpec().name() + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.toString());
            sources.add(path.toString());
        }
        for (var entry : Map.of("Probe", PROBE, "Policy", POLICY).entrySet()) {
            Path path = java.resolve("fixture/" + entry.getKey() + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue());
            sources.add(path.toString());
        }
        var args =
                new ArrayList<>(
                        List.of(
                                "--release",
                                "21",
                                "-g",
                                "-parameters",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                "-d",
                                classes.toString()));
        args.addAll(sources);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, args.toArray(String[]::new)));
        var loader =
                new URLClassLoader(
                        new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
        var events = new DefaultListableBeanFactory().getBeanProvider(EventHandler.class);
        Object read = loader.loadClass("mvc.usecase.Read").getConstructors()[0].newInstance(events);
        Object submit =
                loader.loadClass("mvc.usecase.Submit").getConstructors()[0].newInstance(events);
        Object maybe =
                loader.loadClass("mvc.usecase.Maybe").getConstructors()[0].newInstance(events);
        Object controller =
                loader.loadClass("mvc.adapter.inbound.http.Page")
                        .getConstructors()[0]
                        .newInstance(read, submit, maybe);
        Object advice =
                loader.loadClass("mvc.adapter.inbound.http.PageErrors")
                        .getConstructors()[0]
                        .newInstance(controller);
        MockMvc web =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(advice)
                        .setViewResolvers(
                                (name, locale) ->
                                        new View() {
                                            @Override
                                            public String getContentType() {
                                                return "text/html";
                                            }

                                            @Override
                                            public void render(
                                                    Map<String, ?> model,
                                                    jakarta.servlet.http.HttpServletRequest request,
                                                    jakarta.servlet.http.HttpServletResponse
                                                            response)
                                                    throws Exception {
                                                response.setContentType("text/html");
                                                response.getWriter()
                                                        .write("<html>MVC fixture</html>");
                                            }
                                        })
                        .build();
        return new Fixture(loader, web, loader.loadClass("fixture.Probe"));
    }

    private record Fixture(URLClassLoader loader, MockMvc web, Class<?> probe)
            implements AutoCloseable {
        int count(String field) throws Exception {
            return probe.getField(field).getInt(null);
        }

        void flag(String field) throws Exception {
            probe.getField(field).setBoolean(null, true);
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }
    }

    private MockHttpServletRequestBuilder submit(String text) {
        return post("/submit")
                .contentType("application/x-www-form-urlencoded")
                .param("title", text);
    }

    private static InboundHttpErrors.Body error(MvcResult result) {
        return (InboundHttpErrors.Body)
                Objects.requireNonNull(result.getModelAndView()).getModel().get("error");
    }

    @Test
    @Timeout(60)
    void mvcArgumentMappingsHaveExecutableBreakpointsAndTheirActualWireLocals() throws Exception {
        try (var fixture = fixture()) {
            var result = new VernacCompiler().compileProject(temporary.resolve("src/main/vernac"));
            var target =
                    result.debugMap().entries().stream()
                            .filter(
                                    e ->
                                            e.javaClass().equals("mvc.adapter.inbound.http.Page")
                                                    && e.method().equals("__submit"))
                            .findFirst()
                            .orElseThrow();
            var connector = Bootstrap.virtualMachineManager().defaultConnector();
            var options = connector.defaultArguments();
            options.get("main").setValue("fixture.Probe");
            options.get("options")
                    .setValue(
                            "-classpath \""
                                    + temporary.resolve("classes")
                                    + java.io.File.pathSeparator
                                    + System.getProperty("java.class.path")
                                    + "\"");
            var vm = connector.launch(options);
            int stops = 0;
            boolean finished = false;
            try {
                var prepare = vm.eventRequestManager().createClassPrepareRequest();
                prepare.addClassFilter(target.javaClass());
                prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                prepare.enable();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
                while (!finished && System.nanoTime() < deadline) {
                    var events = vm.eventQueue().remove(1000);
                    if (events == null) continue;
                    for (var event : events) {
                        if (event instanceof ClassPrepareEvent ready) {
                            var location =
                                    ready
                                            .referenceType()
                                            .locationsOfLine(target.javaLine())
                                            .stream()
                                            .filter(l -> l.method().name().equals("__submit"))
                                            .findFirst()
                                            .orElseThrow();
                            var breakpoint =
                                    vm.eventRequestManager().createBreakpointRequest(location);
                            breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                            breakpoint.enable();
                        } else if (event instanceof BreakpointEvent stopped) {
                            stops++;
                            var frame = stopped.thread().frame(0);
                            assertEquals("__submit", stopped.location().method().name());
                            var wire = frame.visibleVariableByName("__wire0");
                            assertNotNull(wire);
                            assertEquals(
                                    "parcel", ((StringReference) frame.getValue(wire)).value());
                        } else if (event instanceof VMDeathEvent
                                || event instanceof VMDisconnectEvent) finished = true;
                    }
                    if (!finished) events.resume();
                }
                assertTrue(finished);
                assertEquals(1, stops);
                assertTrue(vm.process().waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, vm.process().exitValue());
            } finally {
                try {
                    vm.dispose();
                } catch (VMDisconnectedException ignored) {
                }
                if (!vm.process().waitFor(10, TimeUnit.SECONDS)) vm.process().destroyForcibly();
            }
        }
    }

    @Test
    void renderRedirectAbsentAndExactWireDefaultsWork() throws Exception {
        try (var f = fixture()) {
            var board =
                    f.web.perform(get("/"))
                            .andExpect(status().isOk())
                            .andExpect(view().name("board"))
                            .andReturn();
            assertTrue(
                    board.getModelAndView()
                            .getModel()
                            .get("page")
                            .toString()
                            .contains("Fresh page 1"));
            f.web.perform(submit("valid"))
                    .andExpect(status().isSeeOther())
                    .andExpect(redirectedUrl("/"))
                    .andExpect(flash().attribute("notice", "Saved."));
            f.web.perform(get("/maybe"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("missing"));
            f.web.perform(get("/").param("page", "abc")).andExpect(status().isBadRequest());
            f.web.perform(get("/").param("page", "0")).andExpect(status().isConflict());
            assertEquals(1, f.count("submits"));
        }
    }

    @Test
    void redisplayPreservesOnlyDeclaredNonSensitiveInputsAndDoesNotRepeatThePost()
            throws Exception {
        try (var f = fixture()) {
            var result =
                    f.web.perform(
                                    submit("invalid")
                                            .param("password", "PRIVATE_PASSWORD")
                                            .param("note", "keep this")
                                            .param("undeclared", "PRIVATE_EXTRA")
                                            .header("Authorization", "PRIVATE_AUTH"))
                            .andExpect(status().isUnprocessableEntity())
                            .andExpect(view().name("board"))
                            .andReturn();
            assertEquals("FORM_VALIDATION", error(result).code());
            assertEquals(
                    Map.of("form.title", "invalid", "form.note", "keep this"),
                    result.getModelAndView().getModel().get("inputs"));
            assertFalse(error(result).message().contains("PRIVATE"));
            assertEquals(1, f.count("submits"));
            assertEquals(1, f.count("loads"));
            f.web.perform(submit("one").param("title", "two"))
                    .andExpect(status().isBadRequest())
                    .andExpect(view().name("board"));
            assertEquals(1, f.count("submits"));
            assertEquals(2, f.count("loads"));
            f.web.perform(post("/submit").contentType("application/x-www-form-urlencoded"))
                    .andExpect(status().isBadRequest())
                    .andExpect(view().name("board"));
        }
    }

    @Test
    void sharedTypedErrorPoliciesChooseSafeHtmlResponsesAndTechnicalFailuresAreLogged()
            throws Exception {
        Logger logger = Logger.getLogger("org.vernac.runtime.http.inbound.MvcResponses");
        List<LogRecord> records = new ArrayList<>();
        boolean parents = logger.getUseParentHandlers();
        var handler =
                new Handler() {
                    public void publish(LogRecord record) {
                        records.add(record);
                    }

                    public void flush() {}

                    public void close() {}
                };
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try (var f = fixture()) {
            for (var entry :
                    Map.of(
                                    "reject",
                                    409,
                                    "other-reject",
                                    422,
                                    "missing",
                                    404,
                                    "conflict",
                                    409,
                                    "timeout",
                                    503,
                                    "bug",
                                    500)
                            .entrySet()) {
                var response =
                        f.web.perform(submit(entry.getKey()))
                                .andExpect(status().is(entry.getValue()))
                                .andReturn();
                assertFalse(error(response).message().contains("PRIVATE"));
            }
            var custom =
                    f.web.perform(
                                    post("/custom")
                                            .contentType("application/x-www-form-urlencoded")
                                            .param("title", "invalid"))
                            .andExpect(status().isConflict())
                            .andReturn();
            assertEquals("CUSTOM_CONFLICT", error(custom).code());
            f.flag("failPolicy");
            var fallback =
                    f.web.perform(
                                    post("/custom")
                                            .contentType("application/x-www-form-urlencoded")
                                            .param("title", "invalid"))
                            .andExpect(status().isInternalServerError())
                            .andReturn();
            assertEquals("INTERNAL_ERROR", error(fallback).code());
            assertTrue(
                    records.stream()
                                    .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                                    .count()
                            >= 3);
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(parents);
        }
    }

    @Test
    void failedRedisplayStopsAtTheFallbackWithoutRecursionOrRetry() throws Exception {
        Logger logger = Logger.getLogger("org.vernac.runtime.http.inbound.MvcResponses");
        boolean parents = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        try (var f = fixture()) {
            f.flag("failLoad");
            var result =
                    f.web.perform(submit("invalid"))
                            .andExpect(status().isInternalServerError())
                            .andExpect(view().name("problem"))
                            .andReturn();
            assertEquals("INTERNAL_ERROR", error(result).code());
            assertEquals(1, f.count("submits"));
            assertEquals(1, f.count("loads"));
            assertEquals(
                    Map.of("form.title", "invalid"),
                    result.getModelAndView().getModel().get("inputs"));
        } finally {
            logger.setUseParentHandlers(parents);
        }
    }
}
