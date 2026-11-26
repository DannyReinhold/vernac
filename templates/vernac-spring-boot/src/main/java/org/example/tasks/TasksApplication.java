package org.example.tasks;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.ModelAndView;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@NullMarked
public class TasksApplication {
    @Bean
    ErrorViewResolver taskErrorView() {
        String template = "vernac-generated/" + TasksApplication.class.getPackageName().replace('.', '/') + "/TaskProblem";
        return (request, status, model) -> new ModelAndView(template, status);
    }

    public static void main(String[] args) {
        SpringApplication.run(TasksApplication.class, args);
    }
}
