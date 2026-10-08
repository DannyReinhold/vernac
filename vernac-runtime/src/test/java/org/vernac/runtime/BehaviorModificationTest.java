// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BehaviorModificationTest {
    @Test void nestedChangesValidateFirstAndPublishOneTimePerChangedOwner() {
        Object parent = new Object(), child = new Object(), unrelated = new Object();
        List<String> calls = new ArrayList<>(); List<Instant> times = new ArrayList<>();
        BehaviorModification.run(parent, () -> calls.add("parent-check"), t -> { calls.add("parent-time"); times.add(t); }, () -> {
            BehaviorModification.run(unrelated, () -> calls.add("unrelated-check"), t -> { throw new AssertionError("unchanged sibling"); }, () -> {});
            for (int i = 0; i < 2; i++) BehaviorModification.run(child, () -> calls.add("child-check"), t -> { calls.add("child-time"); times.add(t); },
                    () -> BehaviorModification.changed(child));
            assertThat(calls).isEmpty();
        });
        assertThat(calls).containsExactly("child-check", "unrelated-check", "parent-check", "parent-time", "child-time");
        assertThat(times).hasSize(2); assertThat(times.get(0)).isEqualTo(times.get(1));
    }
    @Test void failureDoesNotPublishTimestampsAndDoesNotRollbackWrites() {
        int[] state = {0}; Object owner = new Object(); List<Instant> times = new ArrayList<>();
        assertThatThrownBy(() -> BehaviorModification.run(owner, () -> { throw new DomainValidationException("bad"); }, times::add, () -> {
            state[0] = 1; BehaviorModification.changed(owner);
        })).isInstanceOf(DomainValidationException.class);
        assertThat(state[0]).isEqualTo(1); assertThat(times).isEmpty();
        assertThatThrownBy(() -> BehaviorModification.run(owner, () -> {}, times::add, () -> {
            state[0] = 2; BehaviorModification.changed(owner); throw new IllegalStateException("body");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state[0]).isEqualTo(2); assertThat(times).isEmpty();
        BehaviorModification.run(owner, () -> {}, times::add, () -> BehaviorModification.changed(owner));
        assertThat(times).hasSize(1);
    }
    @Test void noOpAndClosedViewsNeverPublishChanges() {
        Object owner = new Object(); Runnable[] guard = new Runnable[1]; List<Instant> times = new ArrayList<>();
        BehaviorModification.run(owner, () -> {}, times::add, () -> guard[0] = BehaviorModification.accessGuard());
        assertThat(times).isEmpty();
        assertThatThrownBy(guard[0]::run).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BehaviorModification.changed(owner)).isInstanceOf(IllegalStateException.class);
    }
}
