// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.intellij.xdebugger.breakpoints.XBreakpoint;

import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

class VernacBreakpointPositionTest {
    @Test
    @SuppressWarnings("unchecked")
    void positionCallbackDoesNotReenterTheBreakpointOrReplaceItsCoordinates() {
        var breakpoint =
                (XBreakpoint<JavaLineBreakpointProperties>)
                        Proxy.newProxyInstance(
                                XBreakpoint.class.getClassLoader(),
                                new Class<?>[] {XBreakpoint.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError(
                                            "Position callback must not call " + method.getName());
                                });
        assertNull(new VernacLineBreakpointType().getSourcePosition(breakpoint));
    }
}
