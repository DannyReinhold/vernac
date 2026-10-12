// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
                namespace com.example.domain;
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
                namespace com.example.domain;
                value User(String email, String email);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Duplicate field name 'email'");
    }

    @Test
    @DisplayName("Meldet nicht auflösbare Typen mit Zeilenangabe")
    void shouldRejectUnresolvedTypes() {
        String dsl = """
                namespace com.example.domain;
                value Order(UnknownType payload);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Unknown type 'UnknownType'");
    }

    @Test
    @DisplayName("Sammelt mehrere Fehler in einem Durchlauf")
    void shouldCollectMultipleErrors() {
        String dsl = """
                namespace com.example.domain;
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
                namespace com.example.domain;
                id OrderId;
                value OrderData(String payload);
                
                repository for OrderData {
                };
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Repository requires an aggregate root");
    }

    @Test
    @DisplayName("Verhindert mut-Felder in Events, da Events immutable Fakten sind")
    void shouldRejectMutableFieldsInEvents() {
        String dsl = """
                namespace com.example.domain;
                event SomethingHappened(mut String status);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Invalid, mutable or reserved event field: status");
    }

    @Test
    @DisplayName("Verhindert Java-Keywords in Namespaces")
    void shouldRejectJavaKeywordsInNamespace() {
        String dsl = """
                namespace com.example.int.domain;
                id ProjectId;
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Invalid namespace 'com.example.int.domain'");
    }

    @Test
    @DisplayName("Verhindert Java-Keywords als Typ-Namen")
    void shouldRejectJavaKeywordsAsTypeName() {
        String dsl = """
                namespace com.example.domain;
                value class(String payload);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Invalid type name: class");
    }

    @Test
    @DisplayName("Verhindert Java-Keywords als Feld- oder Parameter-Namen")
    void shouldRejectJavaKeywordsAsFieldNames() {
        String dsl = """
                namespace com.example.domain;
                id ProjectId;
                value ProjectName(String value);
                aggregate Project[ProjectId](ProjectName name, ProjectName int);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Field name 'int' conflicts with the generated entity API or is not a Java identifier");
    }

    @Test
    @DisplayName("Verhindert optionale primitive Typen und schlägt Wrapper-Typ vor")
    void shouldRejectOptionalPrimitives() {
        String dsl = """
                namespace com.example.domain;
                value Money(int? amount);
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Primitive type 'int' cannot be optional. Use 'Integer?' instead.");
    }

    @Test void shouldRejectDuplicateDependencyNames() {
        assertThatThrownBy(() -> compiler.compileSource("""
            namespace demo; id OrderId;
            usecase Inner() behavior { execute {} }
            usecase Outer(OrderId) uses Inner, Inner behavior { execute {} }
            """)).isInstanceOf(SemanticValidationException.class).hasMessageContaining("Duplicate");
    }
    @Test void shouldRejectNonBeanDependency() {
        assertThatThrownBy(() -> compiler.compileSource("""
            namespace demo; id OrderId;
            usecase Outer(OrderId) uses OrderId dep behavior { execute {} }
            """)).isInstanceOf(SemanticValidationException.class).hasMessageContaining("uses requires");
    }
    @Test void shouldRejectAggregateInTupleReturn() {
        assertThatThrownBy(() -> compiler.compileSource("""
            namespace demo; id OrderId; value Title(String); aggregate Order[OrderId](Title);
            usecase Outer(OrderId) returns (Order order) behavior { execute { return null; } }
            """)).isInstanceOf(SemanticValidationException.class).hasMessageContaining("Usecase inputs/results");
    }

    @Nested
    @DisplayName("Identifier & First-Class ID Validierungen")
    class IdentifierAndIdTests {

        @Test
        @DisplayName("Verhindert Aggregate mit nicht per 'id' deklarierten ID-Typen")
        void shouldRejectAggregateWithUndeclaredIdType() {
            String dsl = """
                    namespace com.example.domain;
                    aggregate Project[ProjectId]();
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Entity/aggregate identity requires a Vernac id type.");
        }

        @Test
        @DisplayName("Verhindert, dass normale Value Objects als ID im Aggregate-Kopf verwendet werden")
        void shouldRejectValueObjectAsAggregateId() {
            String dsl = """
                    namespace com.example.domain;
                    value ProjectId(UUID value);
                    aggregate Project[ProjectId]();
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Entity/aggregate identity requires a Vernac id type.");
        }

        @Test
        @DisplayName("Verhindert, dass rohe Typen wie UUID direkt als ID verwendet werden")
        void shouldRejectRawUuidAsAggregateId() {
            String dsl = """
                    namespace com.example.domain;
                    aggregate Project[UUID]();
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Entity/aggregate identity requires a Vernac id type.");
        }

        @Test
        @DisplayName("Verhindert Entity mit nicht deklariertem ID-Typ")
        void shouldRejectEntityWithUndeclaredIdType() {
            String dsl = """
                    namespace com.example.domain;
                    entity Task[TaskId]();
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Entity/aggregate identity requires a Vernac id type.");
        }

        @Test
        @DisplayName("Verhindert doppelt deklarierte IDs")
        void shouldRejectDuplicateIdDeclarations() {
            String dsl = """
                    namespace com.example.domain;
                    id ProjectId;
                    id ProjectId;
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Duplicate type declaration 'com.example.domain.ProjectId'");
        }
    }

    @Nested
    @DisplayName("Port and adapter contracts")
    class PortAndAdapterTests {
        @Test void rejectsRawJavaPayloads() {
            assertThatThrownBy(()->compiler.compileSource("namespace example; port P { String fetch(); }"))
                .hasMessageContaining("Port signatures require");
        }
        @Test void rejectsAdapterOptionalityMismatch() {
            assertThatThrownBy(()->compiler.compileSource("namespace example; value Text(String); port P { Text? fetch(); } adapter A implements P { behavior { public Text fetch() { return Text.of(\"x\"); } } }"))
                .hasMessageContaining("optionality");
        }
        @Test void rejectsLegacyThrowsSyntax() {
            assertThatThrownBy(()->compiler.compileSource("namespace example; port P { void run() throws Failure; }"))
                .hasMessageContaining("Syntax error");
        }
        @Test void rejectsTransportSchemasInsideDomainPorts() {
            assertThatThrownBy(()->compiler.compileSource("namespace example; port P { schema Dto { String name; } }"))
                .hasMessageContaining("Syntax error");
        }
        @Test void adapterMustImplementAPort() {
            assertThatThrownBy(()->compiler.compileSource("namespace example; value Text(String); adapter A implements Text { behavior {} }"))
                .hasMessageContaining("must implement");
        }
        @Test void rejectsDuplicatePortSignatures() {
            assertThatThrownBy(()->compiler.compileSource("namespace example; port P { void run(); void run(); }"))
                .hasMessageContaining("Duplicate port operation");
        }
        @Test void rejectsJavaKeywordInNamespace() {
            assertThatThrownBy(()->compiler.compileSource("namespace example.int; port P { void run(); }"))
                .isInstanceOf(SemanticValidationException.class);
        }
        @Test void acceptsVernacKeywordInNamespace() {
            assertThat(compiler.compileSource("namespace example.adapter; port P { void run(); } adapter A implements P { behavior { public void run() {} } }").generatedFiles())
                .anySatisfy(file->assertThat(file.packageName()).isEqualTo("example.adapter.adapter.outbound"));
        }
    }

    @Nested
    @DisplayName("DDD Aggregat- & Collection-Regeln")
    class DddCollectionAndAssociationTests {

        @Test
        @DisplayName("Verhindert rohe JDK-Collections in Aggregaten")
        void shouldRejectRawCollectionsInAggregate() {
            String dsl = """
                    namespace com.example.domain;
                    id OrderId;
                    aggregate Order[OrderId](List<String> items);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Generic field type 'List' is not supported in this declaration");
        }

        @Test
        @DisplayName("Verhindert direkte Referenz auf ein anderes Aggregat")
        void shouldRejectDirectAggregateReference() {
            String dsl = """
                    namespace com.example.domain;
                    id CustomerId;
                    aggregate Customer[CustomerId]();
                    
                    id OrderId;
                    aggregate Order[OrderId](Customer customer);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Entities and aggregates cannot contain aggregates or their collections. Reference aggregate IDs instead.");
        }

        @Test
        @DisplayName("Verhindert direkte Referenz auf ein Aggregat innerhalb einer Entity")
        void shouldRejectDirectAggregateReferenceInEntity() {
            String dsl = """
                    namespace com.example.domain;
                    id CustomerId;
                    aggregate Customer[CustomerId]();
                    
                    id ItemId;
                    entity OrderItem[ItemId](Customer buyer);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Entities and aggregates cannot contain aggregates or their collections. Reference aggregate IDs instead.");
        }

        @Test
        @DisplayName("Erkennt Namenskonflikte bei abgeleiteten Collection-Namen")
        void shouldRejectDuplicateCollectionTypeName() {
            String dsl = """
                    namespace com.example.domain;
                    value Tag(String name) list; // default: Tags
                    value Tags(String customHolder);  // Kollision!
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Duplicate type declaration 'com.example.domain.Tags'");
        }

        @Test
        @DisplayName("Erlaubt First-Class Collections von Value Objects im Aggregat")
        void shouldAllowFirstClassValueObjectCollections() {
            String dsl = """
                    namespace com.example.domain;
                    id OrderId;
                    value OrderLine(String sku) list OrderLines;
                    aggregate Order[OrderId](OrderLines lines);
                    """;

            // Sollte fehlerfrei durchlaufen
            assertThat(compiler.compileSource(dsl)).isNotNull();
        }
    }

    @Nested
    @DisplayName("Domain Service Validierungen")
    class DomainServiceTests {

        @Test
        @DisplayName("Verhindert mutable Parameter in Domain Services")
        void shouldRejectMutableParametersInDomainService() {
            String dsl = """
                    namespace com.example.domain;
                    value WattHours(int value);
                    
                    service TariffCalculator behavior { public WattHours calculate(mut WattHours capacity) {
                        return capacity;
                    } }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Invalid service parameter/dependency");
        }

        @Test
        @DisplayName("Verhindert nicht auflösbare Rückgabetypen im Service")
        void shouldRejectUnresolvableReturnType() {
            String dsl = """
                    namespace com.example.domain;
                    value WattHours(int value);
                    
                    service TariffCalculator behavior { public UnknownType calculate(WattHours capacity) {
                        return capacity;
                    } }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Unknown type 'UnknownType'");
        }

        @Test
        @DisplayName("Verhindert Java-Keywords als Service-Namen")
        void shouldRejectJavaKeywordAsServiceName() {
            String dsl = """
                    namespace com.example.domain;
                    service final behavior { public void run() {} }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Invalid type name: final");
        }
    }

    @Nested
    @DisplayName("Enum Value Object Validierungen")
    class EnumValueObjectTests {

        @Test
        @DisplayName("Verhindert doppelte Enum-Konstanten")
        void shouldRejectDuplicateEnumConstants() {
            String dsl = """
                    namespace com.example.domain;
                    value AcMode = ECO | COOL | ECO;
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Duplicate enum constant 'ECO' in 'AcMode'");
        }

        @Test
        @DisplayName("Verhindert nicht mehr unterstützte DB-Persistenzwerte")
        void shouldRejectLegacyDbPersistenceValues() {
            String dsl = """
                    namespace com.example.domain;
                    value AcMode = ECO("eco") | ECONOMY("eco");
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Syntax error:");
        }

        @Test
        @DisplayName("Verhindert Java-Keywords als Enum-Konstante")
        void shouldRejectJavaKeywordsAsEnumConstant() {
            String dsl = """
                    namespace com.example.domain;
                    value AcMode = ECO | final | OFF;
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Invalid Java 21 name 'final'. Use a valid identifier; Java keywords and literals are reserved.");
        }
    }

    @Nested
    @DisplayName("Event Listener Validierungen")
    class ListenerTests {

        @Test
        @DisplayName("Verhindert Listener auf nicht-existierende Events")
        void shouldRejectListenerForUndeclaredEvent() {
            String dsl = """
                    namespace com.example.energy;
                    
                    synchronous listener Alert on UnknownEvent behavior { execute {} }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Listener on requires a non-optional Event type");
        }

        @Test
        @DisplayName("Verhindert doppelte Dependency-Typen im Listener")
        void shouldRejectDuplicateDependencyTypesInListener() {
            String dsl = """
                    namespace com.example.energy;
                    
                    event OrderShipped();
                    service NotificationPort behavior { public void send() {} }
                    synchronous listener Alert on OrderShipped uses NotificationPort port1, NotificationPort port1 behavior { execute {} }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("port1");
        }

        @Test
        @DisplayName("Erlaubt gültigen Listener auf existierendes Event")
        void shouldAllowValidListener() {
            String dsl = """
                    namespace com.example.energy;
                    
                    port NotificationPort { void notifyUser(); }
                    event OrderShipped();
                    synchronous listener Alert on OrderShipped behavior { execute {} }
                    """;

            assertThat(compiler.compileSource(dsl)).isNotNull();
        }
    }
}