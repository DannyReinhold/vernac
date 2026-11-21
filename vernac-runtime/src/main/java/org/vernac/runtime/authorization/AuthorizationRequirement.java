// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Immutable ordered tree; PUBLIC/AUTHENTICATED are standalone, never group alternatives. */
@NullMarked
public sealed interface AuthorizationRequirement {
    enum Mode implements AuthorizationRequirement { PUBLIC, AUTHENTICATED }
    record Permission(PermissionKey key, @Nullable ResourceReference<?> resource) implements AuthorizationRequirement {
        public Permission { key = PermissionKey.of(Objects.requireNonNull(key).qualifiedName()); }
        @Override public String toString() { return "Permission[redacted]"; }
    }
    record All(List<AuthorizationRequirement> children) implements AuthorizationRequirement {
        public All { children = checked(children); }
        @Override public String toString() { return "All[redacted]"; }
    }
    record Any(List<AuthorizationRequirement> children) implements AuthorizationRequirement {
        public Any { children = checked(children); }
        @Override public String toString() { return "Any[redacted]"; }
    }
    static AuthorizationRequirement permission(PermissionKey key) { return new Permission(key, null); }
    static AuthorizationRequirement permission(PermissionKey key, ResourceReference<?> resource) {
        return new Permission(key, Objects.requireNonNull(resource));
    }
    static AuthorizationRequirement all(AuthorizationRequirement... children) { return new All(Arrays.asList(children)); }
    static AuthorizationRequirement any(AuthorizationRequirement... children) { return new Any(Arrays.asList(children)); }
    private static List<AuthorizationRequirement> checked(List<AuthorizationRequirement> supplied) {
        List<AuthorizationRequirement> children = List.copyOf(supplied);
        if (children.isEmpty()) throw new IllegalArgumentException("Authorization groups must not be empty");
        var leaves = new HashSet<Permission>();
        for (var child : children) {
            if (child instanceof Mode) throw new IllegalArgumentException("Public/authenticated requirements must be standalone");
            if (child instanceof Permission leaf && !leaves.add(leaf))
                throw new IllegalArgumentException("Duplicate sibling permission requirement");
        }
        return children;
    }
}
