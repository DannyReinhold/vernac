// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.runtime.events.EventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;

class PaginationHttpExecutionTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    private URLClassLoader compile() throws Exception {
        Path model=root.resolve("model/pageweb/model.vernac");Files.createDirectories(model.getParent());
        try(var resource=getClass().getResourceAsStream("/pagination/model.vernac")) {
            Files.writeString(model,new String(Objects.requireNonNull(resource).readAllBytes(),StandardCharsets.UTF_8));
        }
        Path sources=root.resolve("java"),classes=Files.createDirectories(root.resolve("classes"));
        new VernacCompiler().compileProject(root.resolve("model")).writeTo(sources);
        var args=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var files=Files.walk(sources)) { files.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(args::add); }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        return new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader());
    }
    private MockMvc mvc(URLClassLoader code) throws Exception {
        ObjectProvider<EventHandler> provider=new DefaultListableBeanFactory().getBeanProvider(EventHandler.class);
        Class<?> board=code.loadClass("pageweb.usecase.Board"),single=code.loadClass("pageweb.usecase.Single");
        Object controller=code.loadClass("pageweb.adapter.inbound.http.Api").getConstructor(board,single)
                .newInstance(board.getConstructor(ObjectProvider.class).newInstance(provider),single.getConstructor(ObjectProvider.class).newInstance(provider));
        Object advice=code.loadClass("pageweb.adapter.inbound.http.ApiErrors").getConstructor().newInstance();
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
    }
    @Test void independentPagesKeepTheirKindsAndMetadataWithoutGlobalJacksonModules() throws Exception {
        try(var code=compile()) {
            var mvc=mvc(code);
            var response=mvc.perform(get("/pages").param("firstPage","2").param("secondPage","1").param("pageSize","2")).andReturn().getResponse();
            assertEquals(200,response.getStatus(),response.getContentAsString());
            var body=JSON.readTree(response.getContentAsString());
            assertEquals("Board",body.get("heading").asText());
            assertEquals("C",body.at("/first/items/0/text").asText());
            assertEquals(2,body.at("/first/offset").asLong()); assertEquals(2,body.at("/first/pageNumber").asLong());
            assertEquals(6,body.at("/first/totalElements").asLong()); assertEquals(3,body.at("/first/totalPages").asLong());
            assertEquals("A",body.at("/second/items/0").asText()); assertFalse(body.get("second").has("totalElements"));
            assertEquals("sample-next",body.at("/feed/nextCursor").asText()); assertFalse(body.get("feed").has("offset"));
            assertFalse(response.getContentAsString().contains("request"));
            var dto=code.loadClass("pageweb.adapter.inbound.http.dto.ApiDtos$BoardDto");
            assertEquals(body,JSON.readTree(JSON.writeValueAsString(JSON.readValue(response.getContentAsString(),dto))));
            var last=mvc.perform(get("/pages").param("firstPage","9").param("secondPage","1").param("pageSize","2").param("cursor","sample-next")).andReturn().getResponse();
            assertEquals(200,last.getStatus()); var beyond=JSON.readTree(last.getContentAsString());
            assertEquals(0,beyond.at("/first/items").size()); assertEquals(6,beyond.at("/first/totalElements").asLong());
            assertFalse(beyond.get("feed").has("nextCursor"));
            assertEquals("single",JSON.readTree(mvc.perform(get("/pages/single")).andReturn().getResponse().getContentAsString()).at("/page/items/0").asText());
        }
    }
    @Test void invalidRequestsAndInconsistentWirePagesFailClearly() throws Exception {
        try(var code=compile()) {
            var mvc=mvc(code);
            var invalid=mvc.perform(get("/pages").param("firstPage","1").param("secondPage","1").param("pageSize","2").param("cursor","modified-token")).andReturn().getResponse();
            assertEquals(400,invalid.getStatus());assertEquals("INVALID_CURSOR",JSON.readTree(invalid.getContentAsString()).get("code").asText());
            assertFalse(invalid.getContentAsString().contains("modified-token"));
            assertEquals(422,mvc.perform(get("/pages").param("firstPage","0").param("secondPage","1").param("pageSize","2")).andReturn().getResponse().getStatus());
            var dto=code.loadClass("pageweb.adapter.inbound.http.dto.ApiDtos$BoardDto");
            var response=mvc.perform(get("/pages").param("firstPage","1").param("secondPage","1").param("pageSize","2")).andReturn().getResponse();
            var body=JSON.readTree(response.getContentAsString());
            ((com.fasterxml.jackson.databind.node.ObjectNode)body.get("first")).put("totalElements",0);
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->JSON.readValue(body.toString(),dto));
        }
    }
}
