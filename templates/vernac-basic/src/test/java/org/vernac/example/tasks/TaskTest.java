package org.example.tasks;

import org.example.tasks.domain.Status;
import org.example.tasks.domain.Task;
import org.example.tasks.domain.Title;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTest {

    @Test
    void completesATask() {
        Task task = Task.create(
                Title.of("Try Vernac"),
                Status.PENDING
        );

        assertThat(task.id()).isNotNull();
        assertThat(task.status()).isEqualTo(Status.PENDING);

        task.complete();

        assertThat(task.status()).isEqualTo(Status.COMPLETED);
        assertThat(task.title().value()).isEqualTo("Try Vernac");
    }
}