package example.documents;

import example.documents.adapter.inbound.http.DocumentWeb;
import example.documents.adapter.inbound.http.DocumentWebErrors;
import example.documents.usecase.DeleteDocument;
import example.documents.usecase.ShowDocument;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

public final class DocumentWebFixtures {
    private DocumentWebFixtures() {}

    @Configuration(proxyBeanMethods = false)
    @Import({WorkspaceFixtures.Editor.class, Mvc.class})
    public static class Editor {}

    @Configuration(proxyBeanMethods = false)
    @Import({WorkspaceFixtures.Reader.class, Mvc.class})
    public static class Reader {}

    @Configuration(proxyBeanMethods = false)
    static class Mvc {
        @Bean MockMvc web(ApplicationContext context, ShowDocument show, DeleteDocument delete) {
            var templates = new SpringResourceTemplateResolver();
            templates.setApplicationContext(context);
            templates.setPrefix("classpath:/templates/");
            templates.setSuffix(".html");
            var engine = new SpringTemplateEngine();
            engine.setTemplateResolver(templates);
            var resolver = new ThymeleafViewResolver();
            resolver.setTemplateEngine(engine);
            resolver.setCharacterEncoding("UTF-8");
            var controller = new DocumentWeb(show, delete);
            return MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new DocumentWebErrors(controller))
                    .setViewResolvers(resolver).build();
        }
    }
}
