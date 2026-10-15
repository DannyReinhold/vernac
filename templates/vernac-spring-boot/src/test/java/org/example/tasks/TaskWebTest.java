package org.example.tasks;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.example.tasks.domain.*;
import org.example.tasks.usecase.*;
import org.example.tasks.adapter.inbound.web.*;
import org.example.tasks.adapter.inbound.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.vernac.runtime.AggregateNotFoundException;
import org.vernac.runtime.events.EventHandler;
import org.vernac.runtime.pagination.*;

import java.util.*;

/**
 * Real templates, controller, generated usecases and CSRF; a test-only repository replaces JDBC.
 */
@SpringJUnitWebConfig(TaskWebTest.Config.class)
class TaskWebTest {
    @Autowired WebApplicationContext context;
    @Autowired MemoryTasks repository;
    MockMvc web;

    @BeforeEach
    void setup() {
        repository.tasks.clear();
        web = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createRenderCompleteAndReopenThroughTheActualHtmlForms() throws Exception {
        web.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("A fresh page.")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        web.perform(post("/tasks").contentType("application/x-www-form-urlencoded").with(csrf()).param("title", "<em>A small win</em>"))
                .andExpect(status().is3xxRedirection());
        web.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;em&gt;A small win&lt;/em&gt;")))
                .andExpect(content().string(not(containsString("<em>A small win</em>"))));
        var id = repository.tasks.keySet().iterator().next();
        web.perform(post("/tasks/" + id.asString() + "/complete").with(csrf()))
                .andExpect(status().is3xxRedirection());
        web.perform(get("/")).andExpect(content().string(containsString("Completed")));
        web.perform(post("/tasks/" + id.asString() + "/reopen").with(csrf()))
                .andExpect(status().is3xxRedirection());
        web.perform(get("/")).andExpect(content().string(containsString("Ready when you are")));
    }

    @Test
    void invalidInputAndMissingCsrfAreRejectedAndPaginationIsRendered() throws Exception {
        web.perform(post("/tasks").contentType("application/x-www-form-urlencoded").param("title", "Without token"))
                .andExpect(status().isForbidden());
        web.perform(post("/tasks").contentType("application/x-www-form-urlencoded").with(csrf()).param("title", " "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(
                        content()
                                .string(
                                        containsString(
                                                "Give your task a meaningful title (1–120 characters).")));
        for (int i = 0; i < 13; i++)
            repository.save(Task.create(TaskTitle.of("Step " + i), TaskStatus.OPEN));
        web.perform(get("/"))
                .andExpect(content().string(containsString("Page 1 of 2")))
                .andExpect(content().string(containsString("rel=\"next\"")));
        web.perform(get("/").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Page 2 of 2")));
        web.perform(get("/").param("page", "0")).andExpect(status().isUnprocessableEntity());
        web.perform(post("/tasks/" + UUID.randomUUID() + "/complete").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({WebSecurity.class})
    static class Config {
        @Bean
        MemoryTasks taskRepository() {
            return new MemoryTasks();
        }

        @Bean
        TaskWeb taskWeb(ObjectProvider<EventHandler> events, MemoryTasks tasks) {
            return new TaskWeb(
                    new ReadTasks(events, tasks),
                    new CreateTask(events, tasks),
                    new CompleteTask(events, tasks),
                    new ReopenTask(events, tasks));
        }

        @Bean TaskWebErrors taskWebErrors(TaskWeb web) { return new TaskWebErrors(web); }

        @Bean
        SpringResourceTemplateResolver templates() {
            var resolver = new SpringResourceTemplateResolver();
            resolver.setPrefix("classpath:/templates/");
            resolver.setSuffix(".html");
            resolver.setCharacterEncoding("UTF-8");
            resolver.setTemplateMode("HTML");
            return resolver;
        }

        @Bean
        SpringTemplateEngine engine(SpringResourceTemplateResolver resolver) {
            var engine = new SpringTemplateEngine();
            engine.setTemplateResolver(resolver);
            return engine;
        }

        @Bean
        ThymeleafViewResolver views(SpringTemplateEngine engine) {
            var resolver = new ThymeleafViewResolver();
            resolver.setTemplateEngine(engine);
            resolver.setCharacterEncoding("UTF-8");
            return resolver;
        }
    }

    static class MemoryTasks implements TaskRepository {
        final Map<TaskId, Task> tasks = new LinkedHashMap<>();

        @Override
        public Task save(Task task) {
            tasks.put(task.id(), task);
            return task;
        }

        @Override
        public Task byId(TaskId id) {
            var task = tasks.get(id);
            if (task == null) throw new AggregateNotFoundException(Task.class, id);
            return task;
        }

        @Override
        public void delete(Task task) {
            tasks.remove(task.id());
        }

        @Override
        public CountedOffsetQuery<Task, Tasks> latest() {
            return request -> {
                var sorted =
                        tasks.values().stream()
                                .sorted(
                                        Comparator.comparing(Task::createdAt)
                                                .reversed()
                                                .thenComparing(task -> task.id().asString()))
                                .toList();
                int start = (int) Math.min(request.offset(), sorted.size());
                int end = (int) Math.min((long) start + request.pageSize(), sorted.size());
                return CountedOffsetPage.of(
                        Tasks.of(sorted.subList(start, end)), request, sorted.size());
            };
        }
    }
}
