package org.vernac.compiler.generator;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.assertj.core.api.Assertions.*;

/** Reviewed domain boundary, replacing obsolete per-method REST generation assertions. */
class PortGeneratorTest {
    private final VernacCompiler compiler=new VernacCompiler();
    private String code(String body) {
        return compiler.compileSource("namespace example; value Text(String); "+body).generatedFiles().toString();
    }
    @Test void createsDomainInterfaceWithOptionalBoundaryAndMultipleOperations() {
        assertThat(code("port Archive { Text? read(Text? key); void write(Text); }"))
            .contains("interface Archive","Optional<Text> read(@Nullable Text key)","void write(Text text)","@NullMarked");
    }
    @Test void generatesWholeAdapterWithIsolatedBehaviorAndInvocation() {
        assertThat(code("""
            port Archive { Text read(Text); void write(Text); }
            adapter LocalArchive implements Archive {
              behavior {
                java uses { java.lang.StringBuilder buffer; }
                public Text read(Text) { return Text.of(self.buffer().toString()); }
                public void write(Text) implemented by example.ArchiveBehavior;
                private String suffix() { return "!"; }
              }
            }
            """))
            .contains("implements Archive","LocalArchiveAccess","AdapterInvocation.create(","example.ArchiveBehavior.write(self, text)")
            .doesNotContain("@Transactional","fromExternal");
    }
    @Test void rejectsMissingExtraAndOptionalityMismatchedOperations() {
        for(String methods: new String[]{"", "public void other() {}", "public Text read(Text text) { return text; }"}) {
            assertThatThrownBy(()->code("port Archive { Text? read(Text); } adapter A implements Archive { behavior { "+methods+" } }"))
                .hasMessageContaining("Adapter");
        }
    }
    @Test void rejectsRawJavaEntityAndAggregateCollectionSignatures() {
        assertThatThrownBy(()->code("port P { String read(); }" )).hasMessageContaining("Port signatures require");
        assertThatThrownBy(()->code("id EId; entity E[EId](Text); port P { E read(); }" )).hasMessageContaining("Port signatures require");
        assertThatThrownBy(()->code("id AId; aggregate A[AId](Text) list As; port P { As read(); }"))
            .hasMessageContaining("Port signatures require");
    }
    @Test void rejectsNameAndDependencyAccessorConflicts() {
        assertThatThrownBy(()->code("port P { Text read(Text, Text); }" )).hasMessageContaining("Duplicate");
        assertThatThrownBy(()->code("port P { Text invocation(); }" )).hasMessageContaining("reserved");
        assertThatThrownBy(()->code("port P { Text buffer(); } adapter A implements P { behavior { java uses { java.lang.StringBuilder buffer; } public Text buffer() { return Text.of(\"x\"); } } }"))
            .hasMessageContaining("dependency accessor");
    }
    @Test void rejectsPrivateExternalHelpersAndInvalidTechnicalDependencies() {
        assertThatThrownBy(()->code("port P { void run(); } adapter A implements P { behavior { public void run() {} private void help() implemented by example.Help; } }"))
            .hasMessageContaining("Private adapter helpers");
        assertThatThrownBy(()->code("port P { void run(); } adapter A implements P { behavior { java uses { String client; } public void run() {} } }"))
            .hasMessageContaining("fully qualified");
    }
    @Test void rejectsLegacyNestedAdapterSyntax() {
        assertThatThrownBy(()->code("port P { Text read() { adapter custom Old; } }" )).isInstanceOf(RuntimeException.class);
    }
}
