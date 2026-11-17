// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.format;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.pipeline.VernacCompiler;
class VernacFormatterTest {
    private static List<String> tokens(String text){return new VernacLexer(CharStreams.fromString(text)).getAllTokens().stream().map(t->t.getType()+":"+t.getText()).toList();}
    @Test void nestedDeclarationsBecomeReadableAndKeepExactTokensAndJavaBodies() {
        String java="{\n  // Do not reinterpret { braces } or whitespace inside Java.\n  return Label.of(\"🧭 {\\\"quoted\\\"} \");\n}";
        String source="// 🧭 header\nnamespace sample;permission Access{VIEW;EDIT;}role product.reader{permission Access.VIEW;}value Label(String text);usecase Read() returns Label requires all{permission Access.VIEW;any{permission Access.EDIT;}}behavior{execute "+java+"}";
        String formatted=VernacFormatter.format(source);
        assertNotEquals(source,formatted);assertTrue(formatted.contains("permission Access {\n    VIEW;\n    EDIT;\n}"),formatted);
        assertTrue(formatted.contains("requires all {\n    permission Access.VIEW;\n    any {\n        permission Access.EDIT;"),formatted);
        assertTrue(formatted.contains(java));assertEquals(tokens(source),tokens(formatted));assertEquals(formatted,VernacFormatter.format(formatted));
        assertEquals(new VernacCompiler().compileSource(source).generatedFiles().stream().map(Object::toString).toList(),new VernacCompiler().compileSource(formatted).generatedFiles().stream().map(Object::toString).toList());
    }
    @Test void commentsAndEscapedStringsArePreservedIncludingLineCommentBoundaries() {
        String source="namespace sample; /* multi\n line 🧭 comment */ value Note(String text) validates{require(self.text()!=\"a;//{\\\"\\n\",\"message\");}; // tail\nvalue State=A|B;";
        String result=VernacFormatter.format(source);
        assertEquals(tokens(source),tokens(result));assertEquals(result,VernacFormatter.format(result));assertTrue(result.contains("/* multi\n line 🧭 comment */"));
        assertTrue(result.contains("// tail\n"));
    }
    @Test void brokenSourceIsLeftUntouchedAndCrLfRemainsCrLfOutsideJava() {
        String broken="namespace sample; value Thing(\n";assertEquals(broken,VernacFormatter.format(broken));
        String text="namespace sample;\r\npermission Rights{VIEW;}\r\n";
        String result=VernacFormatter.format(text);assertFalse(result.replace("\r\n","").contains("\n"));assertEquals(result,VernacFormatter.format(result));
    }
    @Test void applicationAndModuleDefinitionsUseTheSameLayout() {
        String application="application app.demo{authorization explicit;activate library.demo;bind app.Port to app.Adapter;}";
        String formatted=VernacFormatter.format(application,VernacFormatter.Kind.APPLICATION);
        assertTrue(formatted.contains("\n    authorization explicit;\n"));assertEquals(tokens(application),tokens(formatted));
        assertEquals(formatted,VernacFormatter.format(formatted,VernacFormatter.Kind.APPLICATION));
        String module="module library.demo{export app.Thing;}";
        assertEquals("module library.demo {\n    export app.Thing;\n}\n",VernacFormatter.format(module,VernacFormatter.Kind.MODULE));
    }
    @Test void everyCurrentSaasModelFormatsIdempotentlyWithoutChangingAToken() throws Exception {
        Path source=Path.of("../examples/saas-document-workspace/src/main/vernac");
        try(var files=Files.walk(source)) {
            for(var file:files.filter(p->p.toString().endsWith(".vernac")).toList()) {
                String original=Files.readString(file),result=VernacFormatter.format(original);
                assertEquals(tokens(original),tokens(result),file.toString());assertEquals(result,VernacFormatter.format(result),file.toString());
            }
        }
    }
}
