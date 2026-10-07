// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.example.tasks;

import org.example.people.domain.OwnerName;
import org.example.tasks.domain.TaskId;
import org.example.tasks.domain.TaskSummary;
import org.example.tasks.domain.Title;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;

import static org.junit.jupiter.api.Assertions.*;

class TaskSummaryTest {
    @Test
    void usesTypesFromMultipleFilesAndNamespaces() {
        TaskId id = TaskId.create();
        Title title = Title.of("Try Vernac");
        TaskSummary assigned = TaskSummary.of(id, title, OwnerName.of("Danny"));
        assertEquals("Danny", assigned.owner().orElseThrow().value());
        assertEquals(title, assigned.title());
        assertEquals(id, assigned.id());
        assertEquals(assigned, TaskSummary.of(id, title, OwnerName.of("Danny")));
        assertTrue(TaskSummary.of(id, title).owner().isEmpty());
    }

    @Test
    void rejectsInvalidValuesThroughTheDomainExceptionContract() {
        assertThrows(DomainValidationException.class, () -> Title.of("  "));
        assertThrows(DomainValidationException.class, () -> Title.of(null));
    }
}
