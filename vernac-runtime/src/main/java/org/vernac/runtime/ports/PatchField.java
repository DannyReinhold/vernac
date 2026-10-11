// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** A field update: leave unchanged, clear, or replace with a non-null value. */
@NullMarked
public final class PatchField<T> {
    private enum State { UNCHANGED, CLEAR, SET }
    private final State state;
    private final @Nullable T value;
    private PatchField(State state, @Nullable T value) { this.state = state; this.value = value; }
    public static <T> PatchField<T> unchanged() { return new PatchField<>(State.UNCHANGED, null); }
    public static <T> PatchField<T> clear() { return new PatchField<>(State.CLEAR, null); }
    public static <T> PatchField<T> set(T value) { return new PatchField<>(State.SET, Objects.requireNonNull(value, "PatchField.set requires a value")); }
    public boolean isUnchanged() { return state == State.UNCHANGED; }
    public boolean isClear() { return state == State.CLEAR; }
    public boolean isSet() { return state == State.SET; }
    public Optional<T> value() { return Optional.ofNullable(value); }
    public <R> PatchField<R> map(Function<? super T, ? extends R> mapping) {
        Objects.requireNonNull(mapping);
        return switch(state) {
            case UNCHANGED -> unchanged();
            case CLEAR -> clear();
            case SET -> set(mapping.apply(Objects.requireNonNull(value)));
        };
    }
    @Override public boolean equals(@Nullable Object other) {
        return other instanceof PatchField<?> field && state == field.state && Objects.equals(value, field.value);
    }
    @Override public int hashCode() { return Objects.hash(state, value); }
    @Override public String toString() { return isSet() ? "PatchField[set=" + value + "]" : "PatchField[" + state.name().toLowerCase(java.util.Locale.ROOT) + "]"; }
}
