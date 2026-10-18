// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.tools.ToolProvider;

class VernacViewInspectorTest {
    @TempDir Path root;

    @Test
    @Timeout(45)
    void readsRealStoredValuesWithoutInvokingComputedGettersAndOnlySkipsPureReads()
            throws Exception {
        var sources =
                Map.of(
                        "example/domain/access/ParcelRead.java",
                                """
                                package example.domain.access;
                                public interface ParcelRead {
                                    String id(); int units(); java.util.Optional<String> note();
                                    String dangerous(); String misleading();
                                }
                                """,
                        "example/domain/access/ParcelWrite.java",
                                """
                                package example.domain.access;
                                public interface ParcelWrite { void units(int value); }
                                """,
                        "example/domain/access/ParcelAccess.java",
                                """
                                package example.domain.access;
                                public interface ParcelAccess extends ParcelRead, ParcelWrite {
                                    interface Input0 { int units(); }
                                }
                                """,
                        "example/domain/Parcel.java",
                                """
                                            package example.domain;
                                            import java.util.Optional;
                                            import example.domain.access.*;
                                            public class Parcel {
                                                String id = "parcel"; int units = 2; String note;
                                                static int sideEffects;
                                                String dangerous() { sideEffects++; return id; }
                                                public String id() { return this.id; }
                                                public int units() { return this.units; }
                                                public Optional<String> note() { return Optional.ofNullable(this.note); }
                                                private class __ReadView implements ParcelRead {
                                                    public String id() { return Parcel.this.id(); }
                                                    public int units() { return Parcel.this.units(); }
                                                    public Optional<String> note() { return Parcel.this.note(); }
                                                    public String dangerous() { return Parcel.this.dangerous(); }
                                                    public String misleading() { return Parcel.this.id(); }
                                                }
                                                private class __AccessView extends __ReadView implements ParcelAccess {
                                                    final Runnable guard = () -> {};
                                                    public void units(int value) { guard.run(); Parcel.this.units = value; }
                                                }
                                                public static void pause(Object... views) { System.out.println("paused"); }
                                                public static void main(String[] args) {
                                                    Parcel parcel = new Parcel();
                                                    String id = args.length == 0 ? "captured" : args[0];
                                                    int units = args.length + 7;
                                                    String note = args.length == 0 ? null : args[0];
                                                    ParcelRead captured = new ParcelRead() {
                                                        public String id() { return id; }
                                                        public int units() { return units; }
                                                        public Optional<String> note() { return Optional.ofNullable(note); }
                                                        public String dangerous() { sideEffects++; return id; }
                                                        public String misleading() { return id; }
                                                    };
                                                    ParcelAccess.Input0 input = new ParcelAccess.Input0() {
                                                        public int units() { return units; }
                                                    };
                                                    pause(parcel.new __ReadView(), parcel.new __AccessView(), captured, input);
                                                    parcel.note = "updated";
                                                    pause(parcel.new __ReadView());
                                                }
                                            }
                                """);
        var options = new ArrayList<>(List.of("--release", "21", "-g", "-d", root.toString()));
        for (var source : sources.entrySet()) {
            Path file = root.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            options.add(file.toString());
        }
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, options.toArray(String[]::new)));
        var connector = Bootstrap.virtualMachineManager().defaultConnector();
        var args = connector.defaultArguments();
        args.get("main").setValue("example.domain.Parcel");
        args.get("options").setValue("-classpath \"" + root + "\"");
        var vm = connector.launch(args);
        int stops = 0;
        boolean finished = false;
        try {
            var prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter("example.domain.Parcel");
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            prepare.enable();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!finished && System.nanoTime() < deadline) {
                var events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                for (var event : events) {
                    if (event instanceof ClassPrepareEvent ready) {
                        var location =
                                ready.referenceType().methodsByName("pause").getFirst().location();
                        var request = vm.eventRequestManager().createBreakpointRequest(location);
                        request.enable();
                    } else if (event instanceof BreakpointEvent stop) {
                        stops++;
                        var views =
                                (ArrayReference)
                                        stop.thread().frame(0).getArgumentValues().getFirst();
                        for (int i = 0; i < views.length(); i++) {
                            var view = (ObjectReference) views.getValue(i);
                            var renderer = new VernacViewRenderer();
                            assertTrue(renderer.isApplicableAsync(view.referenceType()).get(5, TimeUnit.SECONDS));
                            assertFalse(renderer.isApplicableAsync(vm.mirrorOf(0).type()).get(5, TimeUnit.SECONDS));
                            var contract =
                                    VernacViewInspector.contract(view.referenceType())
                                            .orElseThrow();
                            assertEquals(i == 1 ? "Access" : "Read", contract.role());
                            var properties = VernacViewInspector.properties(view);
                            var byName = new HashMap<String, VernacViewInspector.Property>();
                            properties.forEach(p -> byName.put(p.name(), p));
                            assertFalse(byName.containsKey("dangerous"));
                            // Same-named capture is a real stored id, not a computed method
                            // invocation.
                            if (i < 2) assertFalse(byName.containsKey("misleading"));
                            assertFalse(byName.containsKey("guard"));
                            assertFalse(byName.containsKey("this$0"));
                            assertEquals(
                                    i >= 2 ? 7 : 2,
                                    ((IntegerValue) byName.get("units").value()).intValue());
                            if (i != 3) {
                                assertEquals(
                                        i == 2 ? "captured" : "parcel",
                                        ((StringReference) byName.get("id").value()).value());
                                assertTrue(byName.get("note").optional());
                                assertEquals(
                                        "this.note().orElse(null)",
                                        byName.get("note").expression());
                                if (stops == 1) assertNull(byName.get("note").value());
                                else
                                    assertEquals(
                                            "updated",
                                            ((StringReference) byName.get("note").value()).value());
                            }
                            var getter =
                                    view.referenceType().methodsByName("units", "()I").getFirst();
                            assertTrue(VernacViewSteppingFilter.skippable(view, getter));
                            if (i == 1)
                                assertFalse(
                                        VernacViewSteppingFilter.skippable(
                                                view,
                                                view.referenceType()
                                                        .methodsByName("units", "(I)V")
                                                        .getFirst()));
                            if (i < 3)
                                assertFalse(
                                        VernacViewSteppingFilter.skippable(
                                                view,
                                                view.referenceType()
                                                        .methodsByName("dangerous")
                                                        .getFirst()));
                        }
                        var owner = vm.classesByName("example.domain.Parcel").getFirst();
                        assertEquals(
                                0,
                                ((IntegerValue) owner.getValue(owner.fieldByName("sideEffects")))
                                        .intValue());
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent)
                        finished = true;
                }
                if (!finished) events.resume();
            }
            assertTrue(finished);
            assertEquals(2, stops);
            assertTrue(vm.process().waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, vm.process().exitValue());
        } finally {
            try {
                vm.dispose();
            } catch (VMDisconnectedException ignored) {
            }
            if (!vm.process().waitFor(5, TimeUnit.SECONDS)) vm.process().destroyForcibly();
        }
    }
}
