package org.example.tasks;

import static org.junit.jupiter.api.Assertions.*;

import org.example.tasks.domain.*;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;

class TaskTest {
    @Test
    void aTaskCanBeCompletedAndReopenedWithoutChangingItsIdentity() {
        var task = Task.create(TaskTitle.of("Ship one small improvement"), TaskStatus.OPEN);
        var id = task.id();
        task.complete();
        assertEquals(TaskStatus.DONE, task.taskStatus());
        task.reopen();
        assertEquals(TaskStatus.OPEN, task.taskStatus());
        assertEquals(id, task.id());
    }

    @Test
    void meaninglessTitlesAreRejectedByTheDomain() {
        assertThrows(DomainValidationException.class, () -> TaskTitle.of(" "));
        assertThrows(DomainValidationException.class, () -> TaskTitle.of("x".repeat(121)));
    }
}
