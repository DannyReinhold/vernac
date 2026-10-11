// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RestErrorProjectTest {
    @TempDir Path root;
    private String compile(String policy, boolean optional) throws Exception {
        Path folder = Files.createDirectories(root.resolve("demo"));
        Files.writeString(folder.resolve("model.vernac"), """
            namespace demo;
            value Title(String);
            value Rejection = DENIED | OTHER;
            port Remote { Title%s load(); }
            adapter Client implements Remote {
                rest { client http; base baseUri; Title%s load() {
                    GET "/"; response Payload using toTitle; %s
                } }
                schema Payload(String text);
                schema Problem(String code, Meta? meta, int count, boolean flag);
                schema Meta(String? note);
                mapping toTitle: Title <- Payload { string <- source.text; }
            }
            """.formatted(optional ? "?" : "", optional ? "?" : "", policy));
        return new VernacCompiler().compileProject(root).generatedFiles().toString();
    }
    @Test void resolvesTypedRulesAndExactNumbers() throws Exception {
        assertThat(compile("""
            errors { response Problem;
                when status = 422 and (response.count >= -1.25 or not response.flag = true)
                then reject Rejection.DENIED "Denied";
                when response.meta.note is absent then fail remote INVALID_RESPONSE "Missing";
                otherwise default;
            }
            """, false)).contains("ErrorValues.number", "Rejection.DENIED", "errorBody", "addSuppressed", ".flatMap(");
    }
    @Test void diagnosesUnknownOrMistypedFieldsAndLiterals() {
        assertThatThrownBy(() -> compile("""
            errors { response Problem;
                when response.missing = "x" then reject Rejection.DENIED "No";
                when response.count = "two" then fail local CONFIGURATION "No";
                when response.code > "x" then fail remote INVALID_RESPONSE "No";
            }
            """, false)).hasMessageContaining("Unknown or unsupported error response field")
                .hasMessageContaining("literal must match").hasMessageContaining("Only numeric");
    }
    @Test void codesMustBeDeclaredEnumsAndCategoriesMustExist() {
        assertThatThrownBy(() -> compile("""
            errors {
                when status = 403 then reject Rejection.UNKNOWN "No";
                when status = 401 then reject Title.WRONG "No";
                when status = 500 then fail local NO_SUCH_CATEGORY "No";
            }
            """, false)).hasMessageContaining("Unknown business rejection constant")
                .hasMessageContaining("must belong to a Vernac enum").hasMessageContaining("Unknown integration failure category");
    }
    @Test void requiresErrorSchemaAndValidStatusLiteral() {
        assertThatThrownBy(() -> compile("""
            errors {
                when response.code = "x" then reject Rejection.DENIED "No";
                when status = 700 then fail remote REMOTE_FAILURE "No";
            }
            """, false)).hasMessageContaining("Response predicates require").hasMessageContaining("integer from 100 to 599");
    }
    @Test void forbidsShortcutShadowingEvenThroughBooleanExpressions() {
        for (String condition : new String[]{"status = 404", "status >= 400", "not (status != 404)", "status = 401 or response.code = \"x\""})
            assertThatThrownBy(() -> compile("on 404 absent; errors { response Problem; when " + condition + " then reject Rejection.DENIED \"No\"; }", true))
                .hasMessageContaining("Error rule can match 404");
    }
    @Test void permitsProvablyDisjointShortcutRules() throws Exception {
        assertThat(compile("""
            on 404 absent;
            errors { response Problem;
                when status != 404 and response.code = "DENIED" then reject Rejection.DENIED "No";
                when status >= 500 then fail remote REMOTE_FAILURE "Down";
            }
            """, true)).contains("optionalOn404", "Rejection.DENIED");
    }
    @Test void rejectsBothErrorModesAndNonDtoSchema() {
        assertThatThrownBy(() -> compile("errors implemented by demo.Hook; errors { when status = 500 then fail remote REMOTE_FAILURE \"Down\"; }", false))
                .hasMessageContaining("Syntax error");
        assertThatThrownBy(() -> compile("errors { response Title; when status = 500 then fail remote REMOTE_FAILURE \"Down\"; }", false))
                .hasMessageContaining("adapter-local schema");
    }
    @Test void badEscapesProduceSourceDiagnostics() {
        String backslash = Character.toString(92);
        assertThatThrownBy(() -> compile("errors { when status = 500 then fail remote REMOTE_FAILURE \"bad" + backslash + "q\"; }", false))
                .hasMessageContaining("Invalid escape in error-rule string literal");
    }
    @Test void contextualKeywordsDoNotReserveDomainFieldNames() throws Exception {
        Files.createDirectories(root.resolve("status/remote"));
        Files.writeString(root.resolve("status/remote/value.vernac"), "namespace status.remote; value Title(String reject, String fail, String unknown);");
        assertThat(new VernacCompiler().compileProject(root).generatedFiles()).isNotEmpty();
    }
}
