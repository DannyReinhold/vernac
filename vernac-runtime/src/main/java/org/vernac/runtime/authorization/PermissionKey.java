// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/** Stable namespace/declaration/member identity; declaring a key grants nothing. */
@NullMarked
public interface PermissionKey {
    String qualifiedName();

    static PermissionKey of(String name) { return new Identity(name); }

    /** Canonical snapshot, including when supplied by a generated enum. */
    record Identity(String qualifiedName) implements PermissionKey {
        public Identity {
            Objects.requireNonNull(qualifiedName);
            if (qualifiedName.isBlank() || qualifiedName.indexOf('.') < 1
                    || qualifiedName.endsWith(".") || qualifiedName.contains("..")
                    || qualifiedName.codePoints().anyMatch(Character::isWhitespace))
                throw new IllegalArgumentException("A qualified permission identity is required");
        }
    }
}
