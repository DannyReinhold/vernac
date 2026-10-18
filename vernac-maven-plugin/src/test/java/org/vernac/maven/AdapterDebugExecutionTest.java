// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.vernac.compiler.debug.BehaviorSourceMap;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.tools.ToolProvider;

/** Real bytecode line tables and locals for the example's adapter and mapping entry points. */
class AdapterDebugExecutionTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "adapter",
                "helper",
                "out-java",
                "out-fields",
                "in-java",
                "in-fields",
                "binding",
                "event",
                "event-invalid"
            })
    @Timeout(60)
    void vmStopsAtAdapterMappingAndEventRulesWithTheirActualLocals(String kind) throws Exception {
        Path example = Path.of("../examples/debugger"),
                sources = example.resolve("src/main/vernac");
        var result = new VernacCompiler().compileProject(sources);
        String identity =
                "example.debug."
                        + switch (kind) {
                            case "adapter", "helper" ->
                                    "adapter.outbound.__VernacBehavior_LocalLabelArchive";
                            case "out-java" ->
                                    "adapter.outbound.mapping.__VernacMapping_LocalLabelArchive_toLabel";
                            case "out-fields" ->
                                    "adapter.outbound.mapping.LocalLabelArchiveMappings";
                            case "in-java" ->
                                    "adapter.inbound.http.mapping.__VernacMapping_LabelApi_manual";
                            case "in-fields" -> "adapter.inbound.http.mapping.LabelApiMappings";
                            case "binding" -> "adapter.inbound.http.LabelApi";
                            default -> "domain.__VernacValidation_LabelChecked";
                        };
        String method =
                switch (kind) {
                    case "adapter" -> "load";
                    case "helper" -> "normalize";
                    case "out-java" -> "toLabel";
                    case "out-fields" -> "toPayload";
                    case "in-java" -> "manual";
                    case "in-fields" -> "output";
                    case "binding" -> "format";
                    default -> "validate";
                };
        String marker =
                switch (kind) {
                    case "adapter" -> "return LocalLabelArchiveMappings.toLabel(payload);";
                    case "helper" -> "return normalized;";
                    case "out-java" -> "return Label.of(text);";
                    case "in-java" -> "return Reply.of(text);";
                    case "out-fields", "in-fields" -> "text <- source.string;";
                    case "binding" -> "label <- body.text;";
                    default -> "require(";
                };
        var target =
                result.debugMap().entries().stream()
                        .filter(
                                e -> {
                                    try {
                                        return e.javaClass().equals(identity)
                                                && e.method().equals(method)
                                                && Files.readAllLines(sources.resolve(e.source()))
                                                        .get(e.sourceLine() - 1)
                                                        .contains(marker);
                                    } catch (java.io.IOException ex) {
                                        throw new java.io.UncheckedIOException(ex);
                                    }
                                })
                        .findFirst()
                        .orElseThrow();
        Path generated = root.resolve("java");
        result.writeTo(generated);
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "--release",
                                "21",
                                "-g",
                                "-proc:none",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                "-d",
                                classes.toString()));
        for (Path folder : List.of(generated, example.resolve("src/main/java")))
            try (var paths = Files.walk(folder)) {
                paths.filter(p -> p.toString().endsWith(".java"))
                        .map(Path::toString)
                        .forEach(arguments::add);
            }
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, arguments.toArray(String[]::new)));
        var connector = Bootstrap.virtualMachineManager().defaultConnector();
        var options = connector.defaultArguments();
        options.get("main")
                .setValue(
                        "example.debug.demo.AdapterMappingProbe"
                                + (kind.equals("event-invalid") ? " event-invalid" : ""));
        options.get("options")
                .setValue(
                        "-classpath \""
                                + classes
                                + java.io.File.pathSeparator
                                + System.getProperty("java.class.path")
                                + "\"");
        var vm = connector.launch(options);
        int stops = 0;
        boolean finished = false;
        try {
            var prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(identity);
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            prepare.enable();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while (!finished && System.nanoTime() < deadline) {
                var events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                for (var event : events) {
                    if (event instanceof ClassPrepareEvent ready) {
                        assertEquals(
                                target.runtimeHash(),
                                ((StringReference)
                                                ready.referenceType()
                                                        .getValue(
                                                                ready.referenceType()
                                                                        .fieldByName(
                                                                                BehaviorSourceMap
                                                                                        .HASH_FIELD)))
                                        .value());
                        var locations =
                                ready.referenceType().locationsOfLine(target.javaLine()).stream()
                                        .filter(l -> l.method().name().equals(method))
                                        .toList();
                        assertFalse(
                                locations.isEmpty(),
                                kind + " must have executable bytecode in the mapped method");
                        var request =
                                vm.eventRequestManager()
                                        .createBreakpointRequest(locations.getFirst());
                        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        request.enable();
                    } else if (event instanceof BreakpointEvent stopped) {
                        stops++;
                        assertEquals(method, stopped.location().method().name());
                        var frame = stopped.thread().frame(0);
                        String variable =
                                switch (kind) {
                                    case "adapter", "out-java", "in-java" -> "text";
                                    case "helper" -> "normalized";
                                    case "out-fields", "in-fields" -> "source";
                                    case "binding" -> "__body";
                                    default -> "self";
                                };
                        var local = frame.visibleVariableByName(variable);
                        assertNotNull(local, variable);
                        var value = frame.getValue(local);
                        assertNotNull(value);
                        if (value instanceof StringReference s) assertEquals("parcel", s.value());
                        else if (!kind.startsWith("event")) {
                            var object = (ObjectReference) value;
                            String field = kind.equals("binding") ? "text" : "string";
                            assertEquals(
                                    kind.equals("in-fields") ? "PARCEL!" : "parcel",
                                    ((StringReference)
                                                    object.getValue(
                                                            object.referenceType()
                                                                    .fieldByName(field)))
                                            .value());
                        }
                        if (kind.equals("adapter"))
                            assertNotNull(frame.visibleVariableByName("self"));
                        if (kind.equals("helper")
                                || kind.contains("-java")
                                || kind.contains("-fields"))
                            assertNull(frame.visibleVariableByName("self"));
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent)
                        finished = true;
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
