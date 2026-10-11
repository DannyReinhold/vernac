// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.json.DtoContractException;

/** Decoded error value or sanitized decoding diagnostics. Failure is not a fabricated empty DTO. */
@NullMarked
public final class DecodedError<T> {
    private final Optional<T> value;
    private final Optional<DtoContractException> decodingFailure;

    private DecodedError(Optional<T> value, Optional<DtoContractException> decodingFailure) {
        this.value = value;
        this.decodingFailure = decodingFailure;
    }
    public static <T> DecodedError<T> available(T value) {
        return new DecodedError<>(Optional.of(value), Optional.empty());
    }
    public static <T> DecodedError<T> unavailable(DtoContractException error) {
        return new DecodedError<>(Optional.empty(), Optional.of(error));
    }
    public Optional<T> value() { return value; }
    public Optional<DtoContractException> decodingFailure() { return decodingFailure; }
    public ErrorCondition test(Predicate<T> predicate) {
        Objects.requireNonNull(predicate);
        return value.map(v -> ErrorCondition.of(predicate.test(v))).orElse(ErrorCondition.UNAVAILABLE);
    }
}
