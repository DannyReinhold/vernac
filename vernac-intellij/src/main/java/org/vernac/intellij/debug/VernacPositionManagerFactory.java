// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.*;
import com.intellij.debugger.engine.DebugProcess;

public final class VernacPositionManagerFactory extends PositionManagerFactory {
    @Override
    public PositionManager createPositionManager(DebugProcess process) {
        return new VernacPositionManager(process);
    }
}
