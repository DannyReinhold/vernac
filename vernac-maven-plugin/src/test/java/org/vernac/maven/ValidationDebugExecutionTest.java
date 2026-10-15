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
import org.junit.jupiter.params.provider.CsvSource;
import org.vernac.compiler.debug.BehaviorSourceMap;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.tools.ToolProvider;

class ValidationDebugExecutionTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({
        "value,false",
        "value,true",
        "entity,false",
        "entity,true",
        "aggregate,false",
        "aggregate,true",
        "usecase,false",
        "usecase,true",
        "service,false",
        "service,true"
    })
    @Timeout(60)
    void vmStopsOnceBeforePassingOrFailingRuleWithReadSelf(String kind, boolean invalid)
            throws Exception {
        Path example = Path.of("../examples/debugger");
        var result = new VernacCompiler().compileProject(example.resolve("src/main/vernac"));
        String owner =
                switch (kind) {
                    case "value" -> "Units";
                    case "entity" -> "Parcel";
                    case "aggregate" -> "Batch";
                    case "usecase" -> "FormatLabel";
                    default -> "LabelPreparation";
                };
        String identity =
                "example.debug."
                        + (kind.equals("usecase") ? "usecase" : "domain")
                        + ".__VernacValidation_"
                        + owner;
        var entries =
                result.debugMap().entries().stream()
                        .filter(e -> e.javaClass().equals(identity))
                        .toList();
        assertEquals(1, entries.size());
        var target = entries.getFirst();
        var original =
                Files.readAllLines(example.resolve("src/main/vernac").resolve(target.source()));
        assertTrue(original.get(target.sourceLine() - 1).contains("require("));
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
            try (var files = Files.walk(folder)) {
                files.filter(p -> p.toString().endsWith(".java"))
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
                        "example.debug.demo.ValidationMappingProbe "
                                + kind
                                + (invalid ? " invalid" : ""));
        options.get("options")
                .setValue(
                        "-classpath \""
                                + classes
                                + java.io.File.pathSeparator
                                + System.getProperty("java.class.path")
                                + "\"");
        VirtualMachine vm = connector.launch(options);
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
                        var locations = ready.referenceType().locationsOfLine(target.javaLine());
                        assertFalse(
                                locations.isEmpty(), "Rule entry must have executable bytecode");
                        var request =
                                vm.eventRequestManager()
                                        .createBreakpointRequest(locations.getFirst());
                        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        request.enable();
                    } else if (event instanceof BreakpointEvent stopped) {
                        stops++;
                        assertEquals(target.method(), stopped.location().method().name());
                        var frame = stopped.thread().frame(0);
                        var self = frame.visibleVariableByName("self");
                        assertNotNull(self);
                        var value = (ObjectReference) frame.getValue(self);
                        assertNotNull(value);
                        assertTrue(
                                ((ClassType) value.referenceType())
                                        .allInterfaces().stream()
                                                .anyMatch(t -> t.name().contains(".access.")),
                                "Validation must receive its Read/input view");
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent)
                        finished = true;
                }
                if (!finished) events.resume();
            }
            assertTrue(finished, "Validation probe must finish");
            assertEquals(
                    1,
                    stops,
                    "One rule must not create an additional stop on generated throw scaffolding");
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
