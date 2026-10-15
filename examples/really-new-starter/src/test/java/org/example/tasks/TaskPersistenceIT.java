package org.example.tasks;

import static org.junit.jupiter.api.Assertions.*;

import org.example.tasks.domain.*;
import org.example.tasks.usecase.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Explicit opt-in: uses a disposable PostgreSQL database supplied through DB_* variables. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
class TaskPersistenceIT {
    @Autowired CreateTask create;
    @Autowired CompleteTask complete;
    @Autowired ReopenTask reopen;
    @Autowired ReadTasks read;

    @Test
    void flywayAndGeneratedJdbcRepositoriesSupportTheCompleteLifecycle() {
        var title = TaskTitle.of("Database round trip " + java.util.UUID.randomUUID());
        var id = create.execute(title);
        complete.execute(id);
        var card =
                read.execute(PageNumber.of(1)).items().stream()
                        .filter(task -> task.taskId().equals(id))
                        .findFirst()
                        .orElseThrow();
        assertEquals(TaskStatus.DONE, card.taskStatus());
        assertEquals(title, card.taskTitle());
        reopen.execute(id);
        var reopened =
                read.execute(PageNumber.of(1)).items().stream()
                        .filter(task -> task.taskId().equals(id))
                        .findFirst()
                        .orElseThrow();
        assertEquals(TaskStatus.OPEN, reopened.taskStatus());
    }
}
