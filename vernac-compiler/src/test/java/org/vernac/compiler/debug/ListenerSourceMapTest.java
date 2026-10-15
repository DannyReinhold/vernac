// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.debug;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.nio.file.*;
import java.util.*;

class ListenerSourceMapTest {
    @TempDir Path root;

    @Test
    void mapsSynchronousDeferredHelpersAndRepeatedBodiesButNotExternalImplementations()
            throws Exception {
        Path file = root.resolve("example/events/receivers.vernac");
        Files.createDirectories(file.getParent());
        String text =
                """
                namespace example.events;
                // 🧭 Unicode before receiver locations
                value Label(String);
                event Prepared(Label);
                service Policy behavior { public Label choose(Label label) { return label; } }
                synchronous listener Observe on Prepared uses Policy policy behavior {
                    private void earlier(Prepared prepared) {
                        System.nanoTime();
                    }
                    execute {
                        System.nanoTime();
                    }
                }
                deferred listener Deliver on Prepared behavior {
                    execute {
                        String text = event.label().string();
                        System.out.println(text);
                    }
                    private String helper(String text) {
                        return text.toUpperCase();
                    }
                }
                synchronous listener External on Prepared behavior {
                    execute implemented by example.External;
                }
                """
                        .replace("\n", "\r\n");
        Files.writeString(file, text);
        var compiler = new VernacCompiler();
        var result = compiler.compileProject(root);
        var entries =
                result.debugMap().entries().stream()
                        .filter(e -> e.javaClass().contains(".adapter.inbound.event."))
                        .toList();
        assertEquals(
                Set.of(
                        "example.events.adapter.inbound.event.__VernacBehavior_Observe",
                        "example.events.adapter.inbound.event.__VernacBehavior_Deliver"),
                new HashSet<>(entries.stream().map(BehaviorSourceMap.Entry::javaClass).toList()));
        assertEquals(5, entries.size());
        var observe = entries.stream().filter(e -> e.javaClass().endsWith("_Observe")).toList();
        assertTrue(
                observe.stream()
                                .filter(e -> e.method().equals("execute"))
                                .findFirst()
                                .orElseThrow()
                                .sourceLine()
                        > observe.stream()
                                .filter(e -> e.method().equals("earlier"))
                                .findFirst()
                                .orElseThrow()
                                .sourceLine());
        for (var entry : entries) {
            String java =
                    result.generatedFiles().stream()
                            .filter(
                                    f ->
                                            (f.packageName() + "." + f.typeSpec().name())
                                                    .equals(entry.javaClass()))
                            .findFirst()
                            .orElseThrow()
                            .toString();
            assertEquals(
                    text.lines().toList().get(entry.sourceLine() - 1).strip(),
                    java.lines().toList().get(entry.javaLine() - 1).strip());
            assertEquals(BehaviorSourceMap.hash(text), entry.sourceHash());
            assertEquals(BehaviorSourceMap.hash(java), entry.javaHash());
            assertEquals(64, entry.runtimeHash().length());
            assertEquals("example/events/receivers.vernac", entry.source());
        }
        String access =
                result.generatedFiles().stream()
                        .filter(f -> f.typeSpec().name().equals("ObserveAccess"))
                        .findFirst()
                        .orElseThrow()
                        .toString();
        assertTrue(access.contains("Policy policy()"));
        Path output = root.resolve("generated");
        result.writeTo(output);
        assertEquals(result.debugMap(), BehaviorSourceMap.read(output));
        Files.writeString(
                file,
                "namespace example.events; value Label(String); event Prepared(Label); synchronous"
                    + " listener External on Prepared behavior { execute implemented by"
                    + " example.External; }");
        compiler.compileProject(root).writeTo(output);
        assertTrue(BehaviorSourceMap.read(output).entries().isEmpty());
    }
}
