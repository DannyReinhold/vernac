// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import java.util.*;

/** Shared, non-mutating algorithms used by generated domain collections. */
@NullMarked
public final class DomainCollections {
    private DomainCollections() { }

    /** Rejects null without including domain values in the diagnostic. */
    public static <T> T required(T value) {
        if (value == null) throw new DomainValidationException("Collection arguments and elements must not be null.");
        return value;
    }

    /** Takes a structural snapshot, preserving element instances and encounter order. */
    public static <T> List<T> list(Iterable<? extends T> source) {
        required(source);
        List<T> copy = new ArrayList<>();
        for (T item : source) copy.add(required(item));
        return List.copyOf(copy);
    }

    /** First equal instance wins. Iteration order is the order of first occurrence. */
    public static <T> Set<T> set(Iterable<? extends T> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(list(source)));
    }

    public static <T> Optional<T> find(Collection<T> source, T candidate) {
        required(candidate);
        return source.stream().filter(item -> item.equals(candidate)).findFirst();
    }

    public static <T> int count(Collection<T> source, T candidate) {
        required(candidate);
        int count = 0;
        for (T item : source) if (item.equals(candidate)) count++;
        return count;
    }

    public static <T> List<T> plusAll(Collection<T> source, Iterable<? extends T> added) {
        List<T> result = new ArrayList<>(source);
        result.addAll(list(added));
        return result;
    }

    public static <T> List<T> minus(Collection<T> source, T candidate) {
        required(candidate);
        List<T> result = new ArrayList<>(source);
        result.remove(candidate);
        return result;
    }

    /** Membership selection, not multiset subtraction; original instances survive. */
    public static <T> List<T> matching(Collection<T> source, Iterable<? extends T> candidates, boolean retain) {
        Set<T> selected = new HashSet<>(list(candidates));
        return source.stream().filter(item -> selected.contains(item) == retain).toList();
    }

    /** Returns ALL occurrences of every repeated equality group, in original order. */
    public static <T> List<T> duplicates(Collection<T> source) {
        Map<T, Integer> counts = new HashMap<>();
        for (T item : source) counts.merge(item, 1, Integer::sum);
        return source.stream().filter(item -> counts.get(item) > 1).toList();
    }
}
