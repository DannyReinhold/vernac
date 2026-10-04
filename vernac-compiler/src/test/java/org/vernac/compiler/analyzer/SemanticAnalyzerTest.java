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
                id OrderId;
                value OrderData(String payload);
                
                repository for OrderData {
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
                id ProjectId;
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
                id ProjectId;
                value ProjectName(String value);
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

    @Test
    @DisplayName("Verhindert doppelte Dependency-Typen in use-Klauseln")
    void shouldRejectDuplicateDependencyTypes() {
        String dsl = """
                package com.example.app;
                id OrderId;
                aggregate Order[OrderId](String status);
                repository for Order {}
                
                usecase CancelOrder(OrderId id) {
                    use OrderRepository repo1;
                    use OrderRepository repo2;
                }
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Duplicate dependency type 'OrderRepository' in usecase 'CancelOrder'");
    }

    @Test
    @DisplayName("Meldet Fehler, wenn 'from' den Repository-Typ statt der Variablen nutzt")
    void shouldRejectRepositoryTypeInFromClause() {
        String dsl = """
                package com.example.app;
                id OrderId;
                aggregate Order[OrderId](String status);
                repository for Order {}
                
                usecase CancelOrder(OrderId id) {
                    use OrderRepository myRepo;
                    load Order from OrderRepository by id;
                }
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Repository variable 'OrderRepository' used in 'from' clause is not declared with 'use'");
    }

    @Test
    @DisplayName("Verhindert Rückgabe von Aggregat-Instanzen im Tuple-Return")
    void shouldRejectAggregateInTupleReturn() {
        String dsl = """
                package com.example.app;
                id OrderId;
                aggregate Order[OrderId](String status);
                repository for Order {}
                
                usecase CancelOrder(OrderId id) {
                    use OrderRepository;
                    load Order by id;
                    return (Order, id);
                }
                """;

        assertThatThrownBy(() -> compiler.compileSource(dsl))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("Direct return of aggregate root 'Order' in tuple is forbidden");
    }

    @Nested
    @DisplayName("Identifier & First-Class ID Validierungen")
    class IdentifierAndIdTests {

        @Test
        @DisplayName("Verhindert Aggregate mit nicht per 'id' deklarierten ID-Typen")
        void shouldRejectAggregateWithUndeclaredIdType() {
            String dsl = """
                    package com.example.domain;
                    aggregate Project[ProjectId](String name);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Aggregate 'Project' references ID type 'ProjectId' which is not declared with 'id ProjectId;'");
        }

        @Test
        @DisplayName("Verhindert, dass normale Value Objects als ID im Aggregate-Kopf verwendet werden")
        void shouldRejectValueObjectAsAggregateId() {
            String dsl = """
                    package com.example.domain;
                    value ProjectId(UUID value);
                    aggregate Project[ProjectId](String name);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Aggregate 'Project' references ID type 'ProjectId' which is not declared with 'id ProjectId;'");
        }

        @Test
        @DisplayName("Verhindert, dass rohe Typen wie UUID direkt als ID verwendet werden")
        void shouldRejectRawUuidAsAggregateId() {
            String dsl = """
                    package com.example.domain;
                    aggregate Project[UUID](String name);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Aggregate 'Project' references ID type 'UUID' which is not declared with 'id UUID;'");
        }

        @Test
        @DisplayName("Verhindert Entity mit nicht deklariertem ID-Typ")
        void shouldRejectEntityWithUndeclaredIdType() {
            String dsl = """
                    package com.example.domain;
                    entity Task[TaskId](String title);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Entity 'Task' references ID type 'TaskId' which is not declared with 'id TaskId;'");
        }

        @Test
        @DisplayName("Verhindert doppelt deklarierte IDs")
        void shouldRejectDuplicateIdDeclarations() {
            String dsl = """
                    package com.example.domain;
                    id ProjectId;
                    id ProjectId;
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Duplicate type declaration 'ProjectId'");
        }
    }

    @Nested
    @DisplayName("Port & Adapter Analysen")
    class PortAndAdapterTests {

        @Test
        @DisplayName("Verhindert ungültige HTTP-Statuscodes im REST Adapter")
        void shouldRejectInvalidHttpStatusCodes() {
            String dsl = """
                    package com.example.domain;
                    port MyPort {
                        Optional<String> fetch() {
                            adapter rest {
                                on 999 return Optional.empty();
                                on 404 return Optional.empty();
                                on 600 return Optional.empty();
                            }
                        }
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .satisfies(e -> {
                        SemanticValidationException sve = (SemanticValidationException) e;
                        assertThat(sve.diagnostics()).hasSize(2);
                        assertThat(sve.diagnostics().get(0).message()).contains("Invalid HTTP status code or family '999'");
                        assertThat(sve.diagnostics().get(1).message()).contains("Invalid HTTP status code or family '600'");
                    });
        }

        @Test
        @DisplayName("Verhindert 'return empty', wenn die Methode kein Optional zurückgibt")
        void shouldRejectReturnEmptyForNonOptionalMethod() {
            String dsl = """
                    package com.example.domain;
                    port MyPort {
                        String fetch() {
                            adapter rest {
                                on 404 return empty;
                            }
                        }
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Cannot 'return empty' on status '404' because method 'fetch' does not return an Optional");
        }

        @Test
        @DisplayName("Verhindert das Werfen von Exceptions, die nicht in der throws-Klausel stehen")
        void shouldRejectUndeclaredExceptions() {
            String dsl = """
                    package com.example.domain;
                    port MyPort {
                        String fetch() throws NetworkException {
                            adapter rest {
                                on 5xx throw ServerException;
                            }
                        }
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Thrown exception 'ServerException' on status '5xx' is not declared in method signature's throws clause");
        }

        @Test
        @DisplayName("Verhindert Mappings auf nicht-existierende Felder im Schema")
        void shouldRejectMappingToUnknownSchemaField() {
            String dsl = """
                    package com.example.domain;
                    port MyPort {
                        schema MyDto {
                            String validName;
                        }
                        String fetch() {
                            adapter rest {}
                            mapping {
                                domainValue -> MyDto.invalidName;
                            }
                        }
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Field 'invalidName' does not exist in schema 'MyDto'");
        }

        @Test
        @DisplayName("Verhindert Mappings auf nicht-existierende Felder in der Domäne")
        void shouldRejectMappingToUnknownDomainField() {
            String dsl = """
                    package com.example.domain;
                    value Profile(String validName);
                    
                    port MyPort {
                        Profile fetch() {
                            adapter rest {}
                            mapping {
                                response.body -> Profile.invalidName;
                            }
                        }
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Field 'invalidName' does not exist in domain type 'Profile'");
        }

        @Test
        @DisplayName("Verhindert doppelte Schema-Namen in einem Port")
        void shouldRejectDuplicateSchemaNames() {
            String dsl = """
                    package com.example.domain;
                    port MyPort {
                        schema MyDto {
                            String id;
                        }
                        schema MyDto {
                            String name;
                        }
                        String fetch() {
                            adapter rest {}
                        }
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Duplicate schema name 'MyDto' in port 'MyPort'");
        }

        @Test
        @DisplayName("Verhindert Java-Keywords im benutzerdefinierten Adapter-Package")
        void shouldRejectJavaKeywordsInAdapterPackage() {
            String dsl = """
                    package com.example.domain;
                    port MyPort {
                        String fetch() {
                            adapter rest {
                                package com.example.int.myadapter;
                            }
                        }
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Java keyword 'int' cannot be used in package name 'com.example.int.myadapter'");
        }

        @Test
        @DisplayName("Verhindert Vernac-Keywords im benutzerdefinierten Adapter-Package")
        void shouldRejectVernacKeywordsInAdapterPackage() {
            String dsl = """
                    package com.example.domain;
                    port MyPort {
                        String fetch() {
                            adapter rest {
                                package com.example.infra.adapter;
                            }
                        }
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Syntax error at line 5:38 - mismatched input '.' expecting ';'");
        }
    }

    @Nested
    @DisplayName("DDD Aggregat- & Collection-Regeln")
    class DddCollectionAndAssociationTests {

        @Test
        @DisplayName("Verhindert rohe JDK-Collections in Aggregaten")
        void shouldRejectRawCollectionsInAggregate() {
            String dsl = """
                    package com.example.domain;
                    id OrderId;
                    aggregate Order[OrderId](List<String> items);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Raw collection 'List' is not permitted in 'Order'");
        }

        @Test
        @DisplayName("Verhindert direkte Referenz auf ein anderes Aggregat")
        void shouldRejectDirectAggregateReference() {
            String dsl = """
                    package com.example.domain;
                    id CustomerId;
                    aggregate Customer[CustomerId](String name);
                    
                    id OrderId;
                    aggregate Order[OrderId](Customer customer);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Direct reference to aggregate root 'Customer' inside 'Order' is forbidden. Reference external aggregates by their ID type instead.");
        }

        @Test
        @DisplayName("Verhindert direkte Referenz auf ein Aggregat innerhalb einer Entity")
        void shouldRejectDirectAggregateReferenceInEntity() {
            String dsl = """
                    package com.example.domain;
                    id CustomerId;
                    aggregate Customer[CustomerId](String name);
                    
                    id ItemId;
                    entity OrderItem[ItemId](Customer buyer);
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Direct reference to aggregate root 'Customer' inside 'OrderItem' is forbidden. Reference external aggregates by their ID type instead.");
        }

        @Test
        @DisplayName("Erkennt Namenskonflikte bei abgeleiteten Collection-Namen")
        void shouldRejectDuplicateCollectionTypeName() {
            String dsl = """
                    package com.example.domain;
                    value Tag(String name) collection; // default: Tags
                    value Tags(String customHolder);  // Kollision!
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Duplicate type declaration 'Tags'");
        }

        @Test
        @DisplayName("Erlaubt First-Class Collections von Value Objects im Aggregat")
        void shouldAllowFirstClassValueObjectCollections() {
            String dsl = """
                    package com.example.domain;
                    id OrderId;
                    value OrderLine(String sku) collection OrderLines;
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
                    package com.example.domain;
                    value WattHours(int value);
                    
                    service TariffCalculator(mut WattHours capacity) : WattHours {
                        return capacity;
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Domain service parameter 'capacity' cannot be mutable");
        }

        @Test
        @DisplayName("Verhindert nicht auflösbare Rückgabetypen im Service")
        void shouldRejectUnresolvableReturnType() {
            String dsl = """
                    package com.example.domain;
                    value WattHours(int value);
                    
                    service TariffCalculator(WattHours capacity) : UnknownType {
                        return capacity;
                    }
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Cannot resolve type 'UnknownType'");
        }

        @Test
        @DisplayName("Verhindert Java-Keywords als Service-Namen")
        void shouldRejectJavaKeywordAsServiceName() {
            String dsl = """
                    package com.example.domain;
                    service final() {}
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Java keyword 'final' cannot be used as type name");
        }
    }

    @Nested
    @DisplayName("Enum Value Object Validierungen")
    class EnumValueObjectTests {

        @Test
        @DisplayName("Verhindert doppelte Enum-Konstanten")
        void shouldRejectDuplicateEnumConstants() {
            String dsl = """
                    package com.example.domain;
                    value AcMode = ECO | COOL | ECO;
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Duplicate enum constant 'ECO' in 'AcMode'");
        }

        @Test
        @DisplayName("Verhindert doppelte DB-Persistenzwerte")
        void shouldRejectDuplicateDbPersistenceValues() {
            String dsl = """
                    package com.example.domain;
                    value AcMode = ECO("eco") | ECONOMY("eco");
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Duplicate database persistence value 'eco' in enum 'AcMode'");
        }

        @Test
        @DisplayName("Verhindert Java-Keywords als Enum-Konstante")
        void shouldRejectJavaKeywordsAsEnumConstant() {
            String dsl = """
                    package com.example.domain;
                    value AcMode = ECO | final | OFF;
                    """;

            assertThatThrownBy(() -> compiler.compileSource(dsl))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("Java keyword 'final' cannot be used as enum constant name");
        }
    }
}