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

    @Test
    @DisplayName("Meldet Fehler, wenn ein Repository für ein Value Object statt für ein Aggregate deklariert wird")
    void shouldRejectRepositoryForNonAggregate() {
        String dsl = """
                package com.example.domain;
                value OrderId(UUID);
                value OrderData(String payload);
                
                repository for OrderData {
                    table: "order_data";
                };
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Repository target 'OrderData' must be an existing aggregate root");
    }

    @Test
    @DisplayName("Verhindert mut-Felder in Events, da Events immutable Fakten sind")
    void shouldRejectMutableFieldsInEvents() {
        String dsl = """
                package com.example.domain;
                event SomethingHappened(mut String status);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Domain Event 'SomethingHappened' cannot have mutable field 'status'");
    }

    @Test
    @DisplayName("Verhindert Java-Keywords in Package-Namen")
    void shouldRejectJavaKeywordsInPackageName() {
        String dsl = """
                package com.example.int.domain;
                value ProjectId(UUID);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Java keyword 'int' cannot be used in package name 'com.example.int.domain'");
    }

    @Test
    @DisplayName("Verhindert Java-Keywords als Typ-Namen")
    void shouldRejectJavaKeywordsAsTypeName() {
        String dsl = """
                package com.example.domain;
                value class(String payload);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Java keyword 'class' cannot be used as type name");
    }

    @Test
    @DisplayName("Verhindert Java-Keywords als Feld- oder Parameter-Namen")
    void shouldRejectJavaKeywordsAsFieldNames() {
        String dsl = """
                package com.example.domain;
                aggregate Project[ProjectId](ProjectName name, int int);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Java keyword 'int' cannot be used as field name");
    }

    @Test
    @DisplayName("Verhindert optionale primitive Typen und schlägt Wrapper-Typ vor")
    void shouldRejectOptionalPrimitives() {
        String dsl = """
                package com.example.domain;
                value Money(int? amount);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Primitive type 'int' cannot be optional. Use the wrapper type 'Integer?' instead.");
    }
}