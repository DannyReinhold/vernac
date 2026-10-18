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

/**
 * Tests generated handler mapping and real thread boundaries, not durable delivery/transactions.
 */
class ListenerDebugExecutionTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({
        "synchronous,execute",
        "synchronous,uppercase",
        "deferred,execute",
        "deferred,uppercase"
    })
    @Timeout(60)
    void vmStopsInExecuteAndHelperWithTheActualThreadAndStack(String mode, String method)
            throws Exception {
        boolean deferred = mode.equals("deferred"), helper = method.equals("uppercase");
        Path example = Path.of("../examples/debugger"),
                sources = example.resolve("src/main/vernac");
        var result = new VernacCompiler().compileProject(sources);
        String identity =
                "example.debug.adapter.inbound.event.__VernacBehavior_"
                        + (deferred ? "DeliverLabel" : "ObserveLabel");
        var original = Files.readAllLines(sources.resolve("example/debug/label-events.vernac"));
        String statement = helper ? "return upper;" : "System.out.println(upper);";
        var target =
                result.debugMap().entries().stream()
                        .filter(
                                e ->
                                        e.javaClass().equals(identity)
                                                && e.method().equals(method)
                                                && original.get(e.sourceLine() - 1)
                                                        .contains(statement))
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
                        "example.debug.demo.ListenerMappingProbe"
                                + (deferred ? " background" : ""));
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
                        assertFalse(locations.isEmpty());
                        var request =
                                vm.eventRequestManager()
                                        .createBreakpointRequest(locations.getFirst());
                        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        request.enable();
                    } else if (event instanceof BreakpointEvent stopped) {
                        stops++;
                        assertEquals(
                                deferred ? "vernac-listener-mapping-probe" : "main",
                                stopped.thread().name());
                        assertEquals(method, stopped.location().method().name());
                        var frame = stopped.thread().frame(0);
                        assertEquals(
                                "parcel",
                                ((StringReference)
                                                frame.getValue(frame.visibleVariableByName("text")))
                                        .value());
                        assertEquals(
                                "PARCEL",
                                ((StringReference)
                                                frame.getValue(
                                                        frame.visibleVariableByName("upper")))
                                        .value());
                        if (helper) assertNull(frame.visibleVariableByName("self"));
                        else {
                            var receiver = frame.visibleVariableByName("self");
                            assertNotNull(receiver);
                            assertNotNull(frame.getValue(receiver));
                            var payload =
                                    (ObjectReference)
                                            frame.getValue(frame.visibleVariableByName("event"));
                            assertEquals(
                                    "example.debug.domain.LabelPrepared",
                                    payload.referenceType().name());
                        }
                        var stack =
                                stopped.thread().frames().stream()
                                        .filter(
                                                f ->
                                                        f.location()
                                                                .declaringType()
                                                                .name()
                                                                .equals(
                                                                        "example.debug.demo.ListenerMappingProbe"))
                                        .map(f -> f.location().method().name())
                                        .toList();
                        assertEquals(!deferred, stack.contains("main"));
                        assertEquals(!deferred, stack.contains("runSynchronous"));
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
