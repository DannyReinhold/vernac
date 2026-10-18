// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;

@Service(Service.Level.PROJECT)
@State(name = "VernacDebugger", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class VernacDebugSettings
        implements PersistentStateComponent<VernacDebugSettings.State> {
    public static final class State {
        public boolean generatedJava;
    }

    public boolean generatedJava;

    public static VernacDebugSettings get(Project project) {
        return project.getService(VernacDebugSettings.class);
    }

    @Override
    public State getState() {
        var state = new State();
        state.generatedJava = generatedJava;
        return state;
    }

    @Override
    public void loadState(State state) {
        generatedJava = state.generatedJava;
    }
}
