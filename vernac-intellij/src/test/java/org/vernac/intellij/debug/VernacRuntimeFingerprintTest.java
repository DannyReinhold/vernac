// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jdi.*;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.debug.BehaviorSourceMap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

class VernacRuntimeFingerprintTest {
    @Test
    void oldOrUnfingerprintedLoadedClassesNeverUseANewSourceMap() {
        var entry =
                new BehaviorSourceMap.Entry(
                        "example.Behavior",
                        "display",
                        "example/Behavior.java",
                        12,
                        "example/name.vernac",
                        6,
                        "source",
                        "java",
                        "current-build");
        var bound = new VernacDebugSources.Bound(Path.of("."), Path.of("."), entry);
        assertTrue(VernacPositionManager.matches(type("current-build"), bound));
        assertFalse(VernacPositionManager.matches(type("old-build"), bound));
        assertFalse(VernacPositionManager.matches(type(null), bound));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static ReferenceType type(String hash) {
        Field field = proxy(Field.class, (p, m, a) -> null);
        StringReference value =
                proxy(
                        StringReference.class,
                        (p, m, a) -> m.getName().equals("value") ? hash : null);
        return proxy(
                ReferenceType.class,
                (p, m, a) ->
                        switch (m.getName()) {
                            case "fieldByName" -> hash == null ? null : field;
                            case "getValue" -> value;
                            default -> null;
                        });
    }
}
