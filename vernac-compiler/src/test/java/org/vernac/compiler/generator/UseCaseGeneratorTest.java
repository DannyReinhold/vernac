// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.assertj.core.api.Assertions.*;

class UseCaseGeneratorTest {
    @Test void shouldGenerateUseCaseService() {
        var result=new VernacCompiler().compileSource("""
            namespace demo;
            id TourId; value Title(String);
            usecase Rename(TourId, Title? title) returns (TourId, Title? title)
            validates { require(self.title().isPresent(), "Title needed"); }
            behavior {
                execute { return resultFor(tourId, title.orElseThrow()); }
                private Result resultFor(TourId id, Title title) { return Result.of(id,title); }
            }
            """);
        String code=result.generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertThat(code).contains("Propagation.REQUIRED", "Optional<Title>", "interface RenameRead", "private static Rename.Result resultFor", "@NullMarked");
        assertThat(code).doesNotContain("Object execute", "titleRaw");
    }
    @Test void resolvesDependencyAndExternalOperation() {
        var result=new VernacCompiler().compileSource("""
            namespace demo;
            id TourId;
            usecase Inner(TourId) returns TourId behavior { execute { return tourId; } }
            usecase Outer(TourId) returns TourId uses Inner
            behavior { execute implemented by custom.OuterImplementation; }
            """);
        String code=result.generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertThat(code).contains("private final Inner inner", "custom.OuterImplementation.execute(tourId, inner)", "requireResult");
    }
    @Test void rejectsInvalidContracts() {
        String prefix="namespace demo; id TourId; value Title(String); ";
        for(String declaration: java.util.List.of(
                "usecase Bad(String) behavior { execute {} }",
                "usecase Bad(TourId, TourId) behavior { execute {} }",
                "usecase Bad(Title getClass) behavior { execute {} }",
                "usecase Bad() behavior {}",
                "usecase Bad() behavior { execute {} execute {} }",
                "usecase Bad() behavior { execute {} public void extra() {} }",
                "usecase Bad(TourId) uses TourId behavior { execute {} }"))
            assertThatThrownBy(() -> new VernacCompiler().compileSource(prefix+declaration))
                    .isInstanceOf(org.vernac.compiler.analyzer.SemanticValidationException.class);
    }
}
