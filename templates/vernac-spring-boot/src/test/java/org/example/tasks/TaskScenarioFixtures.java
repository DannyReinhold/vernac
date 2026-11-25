package org.example.tasks;

import org.example.tasks.usecase.CreateTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.vernac.runtime.events.EventHandler;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@Configuration(proxyBeanMethods = false)
public class TaskScenarioFixtures {
    static final class Board implements AutoCloseable {
        private final AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();

        Board() {
            web.setServletContext(new MockServletContext());
            web.register(TaskWebTest.Config.class);
            try {
                web.refresh();
            } catch (RuntimeException failure) {
                web.close();
                throw failure;
            }
        }

        @Override public void close() {
            web.close();
        }
    }

    @Bean Board board() {
        return new Board();
    }

    @Bean MockMvc mockMvc(Board board) {
        return MockMvcBuilders.webAppContextSetup(board.web).apply(springSecurity()).build();
    }

    @Bean CreateTask createTask(Board board, ObjectProvider<EventHandler> events) {
        return new CreateTask(events, board.web.getBean(TaskWebTest.MemoryTasks.class));
    }
}
