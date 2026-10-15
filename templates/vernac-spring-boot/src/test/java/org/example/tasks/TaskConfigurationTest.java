package org.example.tasks;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Loads the real profile files without starting a database or Docker. */
class TaskConfigurationTest {
    private ApplicationContextRunner configuration() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/application.yaml", "LOCAL_DB_PORT=55439");
    }

    @Test
    void runWithoutAnActiveProfileUsesLocalDevelopment() {
        configuration().run(context -> {
            var environment = context.getEnvironment();
            assertTrue(List.of(environment.getDefaultProfiles()).contains("local"));
            assertEquals("jdbc:postgresql://localhost:55439/tasks", environment.getProperty("spring.datasource.url"));
            assertEquals("local-development-only", environment.getProperty("spring.datasource.password"));
            assertEquals("true", environment.getProperty("spring.docker.compose.enabled"));
            assertEquals("false", environment.getProperty("server.servlet.session.cookie.secure"));
        });
    }

    @Test
    void explicitProductionUsesExternalSettingsAndReplacesLocalDefaults() {
        configuration().withPropertyValues("spring.profiles.active=production",
                "DB_URL=jdbc:postgresql://external.example/application", "DB_USERNAME=deployment",
                "DB_PASSWORD=test-only-secret").run(context -> {
            var environment = context.getEnvironment();
            assertEquals(List.of("production"), List.of(environment.getActiveProfiles()));
            assertEquals("jdbc:postgresql://external.example/application", environment.getProperty("spring.datasource.url"));
            assertEquals("deployment", environment.getProperty("spring.datasource.username"));
            assertEquals("test-only-secret", environment.getProperty("spring.datasource.password"));
            assertEquals("false", environment.getProperty("spring.docker.compose.enabled"));
            assertEquals("true", environment.getProperty("server.servlet.session.cookie.secure"));
        });
    }

    @Test
    void explicitPersistenceTestProfileUsesTheSuppliedDatabase() {
        configuration().withPropertyValues("spring.profiles.active=persistence-test",
                "DB_URL=jdbc:postgresql://localhost:55440/disposable", "DB_USERNAME=test",
                "DB_PASSWORD=disposable-test-only").run(context -> {
            var environment = context.getEnvironment();
            assertEquals(List.of("persistence-test"), List.of(environment.getActiveProfiles()));
            assertEquals("jdbc:postgresql://localhost:55440/disposable", environment.getProperty("spring.datasource.url"));
            assertEquals("disposable-test-only", environment.getProperty("spring.datasource.password"));
            assertEquals("false", environment.getProperty("spring.docker.compose.enabled"));
        });
    }
}
