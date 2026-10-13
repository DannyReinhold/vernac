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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.runtime.events.EventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;

class InboundErrorPolicyExecutionTest {
    @TempDir Path root;
    private static final String MODEL="""
        namespace policy;
        value Mode(String);
        value Reason = CLOSED;
        usecase Run(Mode) behavior {
            java imports {
                org.vernac.runtime.ports.AdapterInvocation;
                org.vernac.runtime.ports.PortRejectedException;
                org.vernac.runtime.ports.RemoteIntegrationException;
                org.vernac.runtime.ports.IntegrationCategory;
                org.vernac.runtime.DomainValidationException;
            }
            execute {
                switch(mode.string()) {
                    case "reject" -> throw new PortRejectedException(AdapterInvocation.create("Port","Adapter","run"), Reason.CLOSED,"secret rejection");
                    case "timeout" -> throw new RemoteIntegrationException(AdapterInvocation.create("Port","Adapter","run"),IntegrationCategory.TIMEOUT,"secret upstream");
                    case "validate" -> throw new DomainValidationException("secret validation");
                    case "bug" -> throw new IllegalStateException("secret bug");
                }
            }
        }
        adapter Api inbound http uses Run run {
            base "/policy";
            local { GET "/local"; query String mode; execute run {mode <- query.mode;} respond 204;
                errors {when rejection Reason.CLOSED then respond 409; when validation then respond 400 "LOCAL_VALIDATION" "Check this request."; otherwise default;}
            }
            inherited { GET "/inherited"; query String mode; execute run {mode <- query.mode;} respond 204; }
            errors {
                when rejection Reason.CLOSED then respond 422;
                when integration remote TIMEOUT then respond 503 "DEPENDENCY_UNAVAILABLE" "Try again later.";
                when validation then respond 422;
                otherwise default;
            }
        }
        """;
    @Test void generatedPoliciesRunInMvcAndPreserveDefaultsWithoutPublishingExceptionMessages() throws Exception {
        Path source=root.resolve("source/policy/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,MODEL);
        Path java=root.resolve("java"),classes=Files.createDirectories(root.resolve("classes"));
        new VernacCompiler().compileProject(root.resolve("source")).writeTo(java);
        var args=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var files=Files.walk(java)) {files.filter(f->f.toString().endsWith(".java")).map(Path::toString).forEach(args::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        try(var code=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            ObjectProvider<EventHandler> provider=new DefaultListableBeanFactory().getBeanProvider(EventHandler.class);
            var run=code.loadClass("policy.usecase.Run");var instance=run.getConstructor(ObjectProvider.class).newInstance(provider);
            var controller=code.loadClass("policy.adapter.inbound.http.Api").getConstructor(run).newInstance(instance);
            var advice=code.loadClass("policy.adapter.inbound.http.ApiErrors").getConstructor().newInstance();
            var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
            for(var scenario:List.of(
                new String[]{"local","reject","409","CLOSED"},new String[]{"inherited","reject","422","CLOSED"},
                new String[]{"local","timeout","503","DEPENDENCY_UNAVAILABLE"},
                new String[]{"local","validate","400","LOCAL_VALIDATION"},new String[]{"inherited","validate","422","DOMAIN_VALIDATION"},
                new String[]{"local","bug","500","INTERNAL_ERROR"})) {
                var response=mvc.perform(get("/policy/"+scenario[0]).param("mode",scenario[1])).andReturn().getResponse();
                assertEquals(Integer.parseInt(scenario[2]),response.getStatus(),response.getContentAsString());
                assertEquals(scenario[3],new ObjectMapper().readTree(response.getContentAsString()).get("code").asText());
                assertFalse(response.getContentAsString().contains("secret"));
            }
            assertEquals(400,mvc.perform(get("/policy/local")).andReturn().getResponse().getStatus());
            assertEquals(204,mvc.perform(get("/policy/local").param("mode","ok")).andReturn().getResponse().getStatus());
        }
    }
}
