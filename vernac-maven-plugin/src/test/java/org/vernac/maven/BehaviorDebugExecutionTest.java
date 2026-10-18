// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

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

class BehaviorDebugExecutionTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"domain", "usecase", "usecase-helper", "service", "service-helper"})
    @Timeout(60)
    void actualVmHitsMappedBodyLineAndExposesSelfParametersAndLocals(String scenario)
            throws Exception {
        boolean usecase = scenario.startsWith("usecase"),
                service = scenario.startsWith("service"),
                operation = usecase || service,
                helper = scenario.endsWith("-helper");
        Path example = Path.of("../examples/debugger"), source = example.resolve("src/main/vernac");
        var result = new VernacCompiler().compileProject(source);
        Path generated = root.resolve("java");
        result.writeTo(generated);
        String sourceFile =
                "example/debug/"
                        + (usecase ? "format-label" : service ? "label-preparation" : "label")
                        + ".vernac";
        var labelLines = Files.readAllLines(source.resolve(sourceFile));
        String method =
                helper ? "uppercase" : usecase ? "execute" : service ? "prepare" : "decorate";
        String statement =
                helper
                        ? "return result;"
                        : operation
                                ? "return Label.of(result + suffixText);"
                                : "return result + suffix;";
        var target =
                result.debugMap().entries().stream()
                        .filter(
                                e ->
                                        e.source().equals(sourceFile)
                                                && e.method().equals(method)
                                                && labelLines
                                                        .get(e.sourceLine() - 1)
                                                        .contains(statement))
                        .findFirst()
                        .orElseThrow();
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
                        usecase
                                ? "example.debug.demo.UsecaseMappingProbe"
                                : service
                                        ? "example.debug.demo.ServiceMappingProbe"
                                        : "example.debug.demo.DebugWalkthrough");
        options.get("options")
                .setValue(
                        "-classpath \""
                                + classes
                                + java.io.File.pathSeparator
                                + System.getProperty("java.class.path")
                                + "\"");
        VirtualMachine vm = connector.launch(options);
        boolean hit = false;
        try {
            var prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(target.javaClass());
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            prepare.enable();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while (!hit && System.nanoTime() < deadline) {
                var events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                for (var event : events) {
                    if (event instanceof ClassPrepareEvent ready) {
                        var locations = ready.referenceType().locationsOfLine(target.javaLine());
                        assertFalse(locations.isEmpty());
                        var hash =
                                (StringReference)
                                        ready.referenceType()
                                                .getValue(
                                                        ready.referenceType()
                                                                .fieldByName(
                                                                        BehaviorSourceMap
                                                                                .HASH_FIELD));
                        assertEquals(target.runtimeHash(), hash.value());
                        for (var location : locations) {
                            var request =
                                    vm.eventRequestManager().createBreakpointRequest(location);
                            request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                            request.enable();
                        }
                    }
                    if (event instanceof BreakpointEvent stopped) {
                        assertEquals(target.javaLine(), stopped.location().lineNumber());
                        var frame = stopped.thread().frame(0);
                        if (!helper) {
                            assertNotNull(frame.visibleVariableByName("self"));
                            assertNotNull(frame.getValue(frame.visibleVariableByName("self")));
                            assertEquals(
                                    "!",
                                    ((StringReference)
                                                    frame.getValue(
                                                            frame.visibleVariableByName(
                                                                    operation
                                                                            ? "suffixText"
                                                                            : "suffix")))
                                            .value());
                        } else
                            assertNull(
                                    frame.visibleVariableByName("self"),
                                    "Private helpers do not acquire an implicit receiver");
                        assertEquals(
                                "parcel",
                                ((StringReference)
                                                frame.getValue(
                                                        frame.visibleVariableByName("normalized")))
                                        .value());
                        assertEquals(
                                "PARCEL",
                                ((StringReference)
                                                frame.getValue(
                                                        frame.visibleVariableByName("result")))
                                        .value());
                        hit = true;
                    }
                }
                events.resume();
            }
            assertTrue(hit, "The generated behavior breakpoint must be reached");
        } finally {
            try {
                vm.dispose();
            } catch (VMDisconnectedException ignored) {
            }
            if (!vm.process().waitFor(10, TimeUnit.SECONDS)) vm.process().destroyForcibly();
        }
    }
}
