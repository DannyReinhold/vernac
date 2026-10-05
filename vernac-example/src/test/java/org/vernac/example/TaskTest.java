package org.vernac.example;

import org.junit.jupiter.api.Test;
import org.vernac.example.gettingstarted.domain.Status;
import org.vernac.example.gettingstarted.domain.Task;
import org.vernac.example.gettingstarted.domain.Title;

import static org.assertj.core.api.Assertions.assertThat;

public class TaskTest {
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
