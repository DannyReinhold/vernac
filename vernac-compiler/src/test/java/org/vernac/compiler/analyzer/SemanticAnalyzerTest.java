package org.vernac.compiler.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticAnalyzerTest {

    private final VernacCompiler compiler = new VernacCompiler();

    @Test
    @DisplayName("Verhindert mut-Felder in Value Objects")
    void shouldRejectMutableFieldsInValueObjects() {
        String dsl = """
                package com.example.domain;
                value User(String name, mut int age);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(e -> {
                    SemanticValidationException sve = (SemanticValidationException) e;
                    assertThat(sve.diagnostics()).hasSize(1);
                    CompilerDiagnostic diag = sve.diagnostics().getFirst();
                    assertThat(diag.message()).contains("Value Object 'User' cannot have mutable field 'age'");
                    assertThat(diag.location().line()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("Verhindert doppelte Feldnamen")
    void shouldRejectDuplicateFieldNames() {
        String dsl = """
                package com.example.domain;
                value User(String email, String email);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Duplicate field name 'email' in 'User'");
    }

    @Test
    @DisplayName("Meldet nicht auflösbare Typen mit Zeilenangabe")
    void shouldRejectUnresolvedTypes() {
        String dsl = """
                package com.example.domain;
                value Order(UnknownType payload);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Cannot resolve type 'UnknownType'");
    }

    @Test
    @DisplayName("Sammelt mehrere Fehler in einem Durchlauf")
    void shouldCollectMultipleErrors() {
        String dsl = """
                package com.example.domain;
                value User(mut String name, UnknownType extra);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(e -> {
                    SemanticValidationException sve = (SemanticValidationException) e;
                    assertThat(sve.diagnostics()).hasSize(2);
                });
    }
}