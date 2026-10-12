// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;

class InboundHttpContractTest {
    private final VernacCompiler compiler=new VernacCompiler();
    private static final String MODEL="""
        namespace example;
        id TaskId; value Title(String);
        usecase Create(Title) returns TaskId behavior { execute {return TaskId.create();} }
        usecase Complete(TaskId) behavior {execute {}}
        """;
    @Test void generatesScopedControllersAndReusesAclFactories() {
        var result=compiler.compileSource(MODEL+"""
            adapter Api inbound http uses Create create, Complete complete {
                base "/api/tasks";
                create { POST "/"; body Request; execute create {title <- body.title;} respond 201 Response using output; }
                complete {POST "/{id}";path UUID taskId named "id";execute complete {taskId <- path.taskId;} respond 204;}
                schema Request(String title); schema Response(UUID id);
                mapping output: Response <- TaskId {id <- source;}
            }
            """);
        String code=result.generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertTrue(code.contains("package example.adapter.inbound.http;"));
        assertTrue(code.contains("assignableTypes = Api.class"));
        assertTrue(code.contains("RequestMethod.POST"));
        assertTrue(code.contains("TaskId.of(__wire0)"));
        assertTrue(code.contains("Title.of(__body.title())"));
        assertFalse(result.generatedFiles().stream().filter(f->f.typeSpec().name().equals("Api")).findFirst().orElseThrow().toString().contains("@Transactional"));
    }
    @Test void rejectsInvalidBindingsAndInvocationContracts() {
        for(String endpoint:new String[]{
            "x {POST \"/{id}\";execute complete {taskId <- path.id;} respond 204;}",
            "x {POST \"/{id}\";path UUID? id;execute complete {taskId <- path.id;} respond 204;}",
            "x {POST \"/\";execute complete {} respond 204;}",
            "x {POST \"/\";execute missing {} respond 204;}",
            "x {GET \"/\";body Request;execute create {title <- body.title;} respond 201 Response using output;}",
            "x {POST \"/\";body Request;execute create {title <- body.title; title <- body.title;} respond 201 Response using output;}",
            "x {POST \"/\";query String? text;execute create {title <- query.text;} respond 201 Response using output;}",
            "x {POST \"/\";body Request;execute create {title <- body.missing;} respond 201 Response using output;}",
            "x {POST \"/\";body Request;execute create {title <- body.title;} respond 204;}",
            "x {POST \"/\";body Request;execute create {title <- body.title;} respond 204 Response using output;}",
            "x {POST \"/\";body Request;execute create {title <- body.title;} respond 201 Response using missing;}"
        }) {
            assertThrows(SemanticValidationException.class,()->compiler.compileSource(MODEL+"adapter Api inbound http uses Create create, Complete complete {base \"/api\";"+endpoint+"schema Request(String title); schema Response(UUID id); mapping output: Response <- TaskId {id <- source;}}"),endpoint);
        }
    }
    @Test void rejectsAmbiguousRoutesAcrossAdaptersAndOptionalResultsWithoutPolicy() {
        assertThrows(SemanticValidationException.class,()->compiler.compileSource(MODEL+"""
            adapter A inbound http uses Complete complete {base "/api";x {POST "/{id}";path UUID id;execute complete {taskId <- path.id;} respond 204;}}
            adapter B inbound http uses Complete complete {base "/api";x {POST "/{other}";path UUID other;execute complete {taskId <- path.other;} respond 204;}}
            """));
        var error=assertThrows(SemanticValidationException.class,()->compiler.compileSource("""
            namespace example; value Text(String);
            usecase Find() returns Text? behavior {execute {return java.util.Optional.empty();}}
            adapter A inbound http uses Find find {base "/api";get {GET "/";execute find {} respond 200 Response using output;}
                schema Response(String text);mapping output: Response <- Text {text <- source;}}
            """));
        assertTrue(error.getMessage().contains("on absent respond"));
    }
    @Test void javaOriginsAndInlineMappingProjectionPointToInboundDeclarations() {
        String text=MODEL+"""
            adapter Api inbound http uses Create create {
                base "/api";
                post {POST "/";body Request;execute create {title <- body.title;} respond 201 Reply using output;}
                schema Request(String title);schema Reply(UUID id);
                mapping output: Reply <- TaskId {java {return Reply.of(source.value());}}
            }
            """;
        var root=java.nio.file.Path.of(".").toAbsolutePath().normalize();var path=root.resolve("example/inbound.vernac");
        var source=new VernacSourceFile(path,new VernacSourceParser().parse(path.toString(),text));
        var project=new VernacProjectLoader().index(root,java.util.List.of(source));
        var navigation=new org.vernac.compiler.tooling.VernacTypeNavigation();
        assertEquals("Api",navigation.find(project,java.util.Map.of(path,text),"example.adapter.inbound.http.Api").orElseThrow().name());
        assertEquals("Reply",navigation.find(project,java.util.Map.of(path,text),"example.adapter.inbound.http.dto.ApiDtos.Reply").orElseThrow().name());
        assertEquals("output",navigation.findMember(project,java.util.Map.of(path,text),"example.adapter.inbound.http.mapping.ApiMappings",
                new org.vernac.compiler.tooling.VernacTypeNavigation.Member("output",java.util.List.of("example.domain.TaskId"))).orElseThrow().name());
        var blocks=new org.vernac.compiler.tooling.BehaviorJavaProjection().build(project,source,text);
        assertTrue(blocks.stream().anyMatch(b->b.owner().equals("ApiMappingoutput")));
    }

    @Test void invalidCrossFileUsecaseResultsProduceDiagnosticsInsteadOfNullFailures() {
        var root=java.nio.file.Path.of(".").toAbsolutePath().normalize();
        var parser=new VernacSourceParser();
        var bad=new VernacSourceFile(root.resolve("example/bad.vernac"),parser.parse("bad.vernac","namespace example; id Id; aggregate Root[Id](); usecase Bad() returns (Root) behavior {execute {return null;}}"));
        var api=new VernacSourceFile(root.resolve("example/api.vernac"),parser.parse("api.vernac","namespace example; adapter Api inbound http uses Bad bad {base \"/\";get {GET \"/\";execute bad {} respond 200 Reply using output;} schema Reply(UUID id);mapping output:Reply <- Bad.Result {id <- source.root;}}"));
        var project=new VernacProjectLoader().index(root,java.util.List.of(bad,api));
        assertThrows(SemanticValidationException.class,()->new ProjectTypeResolver().resolve(project));
    }

}
