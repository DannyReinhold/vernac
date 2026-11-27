// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Optional runtime activation of compiler-checked layouts and generated templates. */
public final class DevelopmentRevisions {
    private static final ThreadLocal<Scope> PINNED = new ThreadLocal<>();
    private static final ClassValue<Map<Integer, Binding<?>>> BINDINGS = new ClassValue<>() {
        @Override
        protected Map<Integer, Binding<?>> computeValue(Class<?> implementation) {
            return new ConcurrentHashMap<>();
        }
    };

    /** The generated implementation class must have completed initialization before activation. */
    public record BehaviorKey(Class<?> implementation, int program) {
        public BehaviorKey {
            Objects.requireNonNull(implementation);
            if (program < 0) throw new IllegalArgumentException("Negative native behavior program index");
        }
    }

    public static final class BehaviorUpdate {
        private final byte[] layout;
        private final byte[] sourceMap;

        public BehaviorUpdate(byte[] layout, byte[] sourceMap) {
            this.layout = layout.clone();
            this.sourceMap = sourceMap.clone();
        }
    }

    private record Binding<S>(NativeBehavior.Program<S> packaged, List<NativeBehavior.Step<S>> bridge) {
        NativeBehavior.Program<S> link(byte[] layout) throws IOException {
            return new NativeBehavior.Program<>(packaged.formatVersion(), packaged.localCount(),
                    NativeBehaviorLayout.link(new ByteArrayInputStream(layout), bridge));
        }
    }

    private record Revision(long number, Map<NativeBehavior.Program<?>, NativeBehavior.Program<?>> programs,
                            Map<BehaviorKey, byte[]> sources, Map<String, String> templates) { }

    private final AtomicReference<Revision> active =
            new AtomicReference<>(new Revision(0, Map.of(), Map.of(), Map.of()));

    static <S> void register(Class<?> implementation, int program, NativeBehavior.Program<S> packaged,
                             List<NativeBehavior.Step<S>> bridge) {
        BINDINGS.get(implementation).put(program, new Binding<>(packaged, List.copyOf(bridge)));
    }

    public long revision() {
        return active.get().number();
    }

    /**
     * Publishes a complete set of overrides relative to the packaged application. The caller supplies
     * compiler-checked templates and layouts verified against the running application's JVM bindings.
     * All layouts must link before the candidate becomes visible.
     */
    public long activate(long expected, Map<BehaviorKey, BehaviorUpdate> behaviors, Map<String, String> templates)
            throws IOException {
        var previous = active.get();
        if (previous.number() != expected) throw stale(expected);
        var programs = new IdentityHashMap<NativeBehavior.Program<?>, NativeBehavior.Program<?>>();
        var sources = new HashMap<BehaviorKey, byte[]>();
        for (var entry : Map.copyOf(behaviors).entrySet()) {
            var key = entry.getKey();
            var binding = BINDINGS.get(key.implementation()).get(key.program());
            if (binding == null) throw new IOException("Unknown native behavior binding " + key);
            try {
                programs.put(binding.packaged(), binding.link(entry.getValue().layout));
            } catch (IOException failure) {
                throw new IOException("Cannot link native behavior " + key, failure);
            }
            sources.put(key, entry.getValue().sourceMap);
        }
        var next = new Revision(Math.incrementExact(expected), Collections.unmodifiableMap(programs),
                Map.copyOf(sources), Map.copyOf(templates));
        if (!active.compareAndSet(previous, next)) throw stale(expected);
        return next.number();
    }

    private IllegalStateException stale(long expected) {
        return new IllegalStateException("Expected development revision " + expected + " but active revision is " + revision());
    }

    /** Pins the active revision for synchronous work on the calling thread. */
    public Scope pin() {
        var previous = PINNED.get();
        var scope = new Scope(previous != null && previous.owner == this ? previous.revision : active.get(), previous);
        PINNED.set(scope);
        return scope;
    }

    public final class Scope implements AutoCloseable {
        private final DevelopmentRevisions owner = DevelopmentRevisions.this;
        private final Revision revision;
        private final Scope previous;

        private Scope(Revision revision, Scope previous) {
            this.revision = revision;
            this.previous = previous;
        }

        public long revision() {
            return revision.number();
        }

        @Override
        public void close() {
            if (PINNED.get() != this) throw new IllegalStateException("Development scopes must close in opening thread and reverse order");
            if (previous == null) PINNED.remove();
            else PINNED.set(previous);
        }
    }

    public Optional<String> template(String name) {
        var scope = PINNED.get();
        return scope == null || scope.owner != this ? Optional.empty() : Optional.ofNullable(scope.revision.templates().get(name));
    }

    static <S> NativeBehavior.Program<S> select(NativeBehavior.Program<S> packaged) {
        var scope = PINNED.get();
        if (scope == null) return packaged;
        // Replacements are linked and stored against their exact, typed packaged program.
        @SuppressWarnings("unchecked")
        var replacement = (NativeBehavior.Program<S>) scope.revision.programs().get(packaged);
        return replacement == null ? packaged : replacement;
    }

    static InputStream sourceMap(Class<?> implementation, int program) {
        var scope = PINNED.get();
        if (scope != null && program >= 0) {
            var bytes = scope.revision.sources().get(new BehaviorKey(implementation, program));
            if (bytes != null) return new ByteArrayInputStream(bytes);
        }
        return implementation.getResourceAsStream("/" + NativeBehaviorSources.resource(implementation.getName(), program));
    }
}
