// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.runtime.events.EventHandler;
import org.vernac.runtime.http.inbound.InboundHttpErrors;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;

public class InboundHttpExecutionTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String MODEL="""
        namespace webdemo;
        id TaskId;
        value Title(String) validates {require(!self.string().isBlank(), "Private validation details");};
        usecase Create(Title, Title? note) returns (TaskId, Title, Title? note) behavior {
            execute {return Result.of(TaskId.create(), title, note);}
        }
        usecase Complete(TaskId) behavior {execute {}}
        usecase Find(Title) returns Title? behavior {
            execute {return title.string().equals("missing") ? java.util.Optional.empty() : java.util.Optional.of(title);}
        }
        usecase Fail(Title) returns Title behavior {
            execute {
                switch(title.string()) {
                    case "missing": throw new org.vernac.runtime.AggregateNotFoundException(Object.class, "secret");
                    case "conflict": throw new org.springframework.dao.OptimisticLockingFailureException("secret");
                    case "domain": throw new org.vernac.runtime.DomainValidationException("secret");
                    default: throw new IllegalStateException("secret");
                }
            }
        }
        adapter Api inbound http uses Create create, Complete complete, Find find, Fail fail {
            base "/api/tasks";
            create {POST "/";body Request;execute create {title <- body.title;note <- body.note;} respond 201 Created using output;}
            complete {POST "/{id}/complete";path UUID taskId named "id";execute complete {taskId <- path.taskId;} respond 204;}
            find {GET "/find";query String title;execute find {title <- query.title;} respond 200 TextResponse using text;on absent respond 404;}
            fail {GET "/fail";header String reason named "X-Reason";execute fail {title <- header.reason;} respond 200 TextResponse using text;}
            bad {GET "/bad-response";query String title;execute find {title <- query.title;} respond 200 TextResponse using broken;on absent respond 404;}
            schema Request(String title, String? note);
            schema Created(UUID id, String title, String? note);
            schema TextResponse(String text);
            mapping output: Created <- Create.Result {id <- source.taskId;title <- source.title;note <- source.note;}
            mapping text: TextResponse <- Title {text <- source;}
            mapping broken: TextResponse <- Title {java {throw new org.vernac.runtime.DomainValidationException("secret");}}
        }
        """;
    private URLClassLoader compile(String model) throws Exception {
        Path input=root.resolve("model/webdemo/model.vernac");Files.createDirectories(input.getParent());Files.writeString(input,model);
        Path java=root.resolve("java"),classes=Files.createDirectories(root.resolve("classes"));
        new VernacCompiler().compileProject(root.resolve("model")).writeTo(java);
        var args=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var files=Files.walk(java)){files.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"Generated controller, Result mappings and codecs must compile");
        return new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader());
    }
    private MockMvc mvc(URLClassLoader loader,String adapter,String... usecases) throws Exception {
        ObjectProvider<EventHandler> provider=new DefaultListableBeanFactory().getBeanProvider(EventHandler.class);
        var types=new ArrayList<Class<?>>();var beans=new ArrayList<Object>();
        for(String name:usecases){Class<?> type=loader.loadClass("webdemo.usecase."+name);types.add(type);beans.add(type.getConstructor(ObjectProvider.class).newInstance(provider));}
        Class<?> controller=loader.loadClass("webdemo.adapter.inbound.http."+adapter);
        Object bean=controller.getConstructor(types.toArray(Class<?>[]::new)).newInstance(beans.toArray());
        Object advice=loader.loadClass("webdemo.adapter.inbound.http."+adapter+"Errors").getConstructor().newInstance();
        return MockMvcBuilders.standaloneSetup(bean).setControllerAdvice(advice).build();
    }
    @Test void requestsRunTheUsecaseAndMapDeclaredResultsAndAbsence() throws Exception {
        try(var code=compile(MODEL)) {
            MockMvc mvc=mvc(code,"Api","Create","Complete","Find","Fail");
            var response=mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Test\",\"extra\":true}")).andReturn().getResponse();
            assertEquals(201,response.getStatus());var body=JSON.readTree(response.getContentAsString());
            String id=body.get("id").asText();assertDoesNotThrow(()->UUID.fromString(id));assertEquals("Test",body.get("title").asText());assertFalse(body.has("note"));
            assertEquals(204,mvc.perform(post("/api/tasks/"+id+"/complete")).andReturn().getResponse().getStatus());
            assertEquals(404,mvc.perform(get("/api/tasks/find").param("title","missing")).andReturn().getResponse().getStatus());
            assertEquals("Found",JSON.readTree(mvc.perform(get("/api/tasks/find").param("title","Found")).andReturn().getResponse().getContentAsString()).get("text").asText());
            var withNote=mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Test\",\"note\":\"Remark\"}")).andReturn().getResponse();
            assertEquals("Remark",JSON.readTree(withNote.getContentAsString()).get("note").asText());
        }
    }
    @Test void errorsRemainRecognizableWithoutExposingExceptionDetails() throws Exception {
        try(var code=compile(MODEL)) {
            MockMvc mvc=mvc(code,"Api","Create","Complete","Find","Fail");
            assertEquals(400,mvc.perform(post("/api/tasks/not-a-uuid/complete")).andReturn().getResponse().getStatus());
            assertEquals(400,mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
            var validation=mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}")).andReturn().getResponse();
            assertEquals(422,validation.getStatus());assertFalse(validation.getContentAsString().contains("Private validation details"));
            for(var entry:Map.of("missing",404,"conflict",409,"domain",422,"technical",500).entrySet()) {
                var response=mvc.perform(get("/api/tasks/fail").header("X-Reason",entry.getKey())).andReturn().getResponse();
                assertEquals(entry.getValue(),response.getStatus());assertTrue(JSON.readTree(response.getContentAsString()).has("code"));assertFalse(response.getContentAsString().contains("secret"));
            }
            assertEquals(500,mvc.perform(get("/api/tasks/bad-response").param("title","valid")).andReturn().getResponse().getStatus());
            assertEquals(400,mvc.perform(get("/api/tasks/find")).andReturn().getResponse().getStatus());
        }
    }
    public static final class CustomErrors {
        public static InboundHttpErrors.Reply handle(String endpoint,Exception error) {
            return new InboundHttpErrors.Reply(409,"CUSTOM_REJECTION","Rejected at "+endpoint);
        }
    }
    @Test void externalErrorPolicyCanReplaceDefaultsPerAdapter() throws Exception {
        String model=MODEL+"""
            adapter Custom inbound http uses Fail fail {
                base "/custom";
                call {GET "/";query String title;execute fail {title <- query.title;} respond 200 Reply using text;}
                schema Reply(String text);mapping text: Reply <- Title {text <- source;}
                errors implemented by org.vernac.maven.InboundHttpExecutionTest.CustomErrors;
            }
            """;
        try(var code=compile(model)) {
            var response=mvc(code,"Custom","Fail").perform(get("/custom").param("title","domain")).andReturn().getResponse();
            assertEquals(409,response.getStatus());assertEquals("CUSTOM_REJECTION",JSON.readTree(response.getContentAsString()).get("code").asText());
            assertTrue(response.getContentAsString().contains("call"));
        }
    }
}
