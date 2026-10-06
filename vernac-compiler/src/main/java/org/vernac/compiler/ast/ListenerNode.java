// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record ListenerNode(
        SourceLocation location,
        String eventName,
        List<UseDependencyNode> dependencies,
        List<RawJavaStatementNode> statements,
        Optional<String> customPackage
) implements TopLevelDefinition {

    /**
     * Konvention: Generierter Klassenname lautet {@code <EventName>Listener}
     * (z. B. StorageOverheatedListener).
     */
    public String listenerName() {
        return eventName + "Listener";
    }
}