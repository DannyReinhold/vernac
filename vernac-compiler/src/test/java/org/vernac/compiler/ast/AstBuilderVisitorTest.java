// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AstBuilderVisitorTest {

    private CompilationUnitNode parse(String source) {
        return new org.vernac.compiler.pipeline.VernacSourceParser().parse("test.vernac", source);
    }

    @Test
    @DisplayName("Parst Aggregate mit Standard-Id und abgeleiteten sowie expliziten Feldnamen")
    void shouldParseAggregateWithIdAndMutableFields() {
        String src = """
                namespace com.example.domain;
                
                aggregate Project[ProjectId](ProjectName, mut Money budget) validates {
                    require(self.budget().amount().signum() >= 0, "Budget cannot be negative");
                } behavior {
                    modify void assignBudget(Money newBudget) {
                        self.budget(newBudget);
                    }
                };
                
                aggregate Task[TaskId](String title);
                """;

        CompilationUnitNode cu = parse(src);
        assertThat(cu.aggregates()).hasSize(2);

        // Erstes Aggregat: Standard ID-Name "id" und abgeleiteter Feldname "projectName"
        AggregateNode project = cu.aggregates().getFirst();
        assertThat(project.name()).isEqualTo("Project");
        assertThat(project.idDefinition().type().name()).isEqualTo("ProjectId");
        assertThat(project.idDefinition().fieldName()).isEqualTo("id");
        assertThat(project.fields()).hasSize(2);

        FieldNode nameField = project.fields().get(0);
        assertThat(nameField.name()).isEqualTo("projectName");
        assertThat(nameField.isMutable()).isFalse();

        FieldNode budgetField = project.fields().get(1);
        assertThat(budgetField.name()).isEqualTo("budget");
        assertThat(budgetField.isMutable()).isTrue();

        assertThat(project.validations()).hasSize(1);
        assertThat(project.validations().getFirst().condition()).isEqualTo("self.budget().amount().signum() >= 0");
        assertThat(project.methods()).hasSize(1);
        assertThat(project.methods().getFirst().name()).isEqualTo("assignBudget");

        // Zweites Aggregat: deterministischer ID-Name "id"
        AggregateNode task = cu.aggregates().get(1);
        assertThat(task.name()).isEqualTo("Task");
        assertThat(task.idDefinition().type().name()).isEqualTo("TaskId");
        assertThat(task.idDefinition().fieldName()).isEqualTo("id");
        assertThat(task.fields().getFirst().isMutable()).isFalse();
    }

    @Nested
    @DisplayName("0. Identifier Types")
    class IdentifierTests {

        @Test
        @DisplayName("Parst 'id <Name>;' als IdDeclarationNode")
        void shouldParseIdDeclarations() {
            String src = """
                    namespace com.example.domain;
                    
                    id ProjectId;
                    id TaskId;
                    """;

            CompilationUnitNode cu = parse(src);
            var idDeclarations = cu.definitions().stream()
                    .filter(d -> d instanceof IdDeclarationNode)
                    .map(d -> (IdDeclarationNode) d)
                    .toList();

            assertThat(idDeclarations).hasSize(2);
            assertThat(idDeclarations.get(0).name()).isEqualTo("ProjectId");
            assertThat(idDeclarations.get(1).name()).isEqualTo("TaskId");
        }
    }

    @Nested
    @DisplayName("1. Value Objects")
    class ValueObjectTests {

        @Test
        @DisplayName("Leitet auch bei Single Value Objects den Feldnamen aus dem Typ ab")
        void shouldDeriveTypeBasedFieldNameForSingleParam() {
            String src = """
                    namespace com.example.domain;
                    value ProjectId(UUID);
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.valueObjects()).hasSize(1);

            ValueObjectNode vo = cu.valueObjects().getFirst();
            assertThat(vo.name()).isEqualTo("ProjectId");
            assertThat(vo.fields()).hasSize(1);
            assertThat(vo.fields().getFirst().name()).isEqualTo("uuid");
            assertThat(vo.fields().getFirst().type().name()).isEqualTo("UUID");
        }

        @Test
        @DisplayName("Rejects removed package overrides in Value Objects")
        void shouldRejectValueObjectWithPackageOverride() {
            String src = """
                    namespace com.example.domain;
                    value SharedId(UUID) behavior {
                        package com.example.shared.kernel;
                    }
                    """;

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    new org.vernac.compiler.pipeline.VernacSourceParser().parse("override.vernac", src))
                    .isInstanceOf(org.vernac.compiler.analyzer.SemanticValidationException.class)
                    .hasMessageContaining("Syntax error");
        }

        @Test
        void shouldParseValueObjectWithValidation() {
            String src = """
                    namespace com.example.domain;
                    
                    value Money(BigDecimal amount, Currency currency) validates {
                        require(amount >= 0, "Amount must be positive");
                        require(currency != null, "Currency required");
                    };
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.valueObjects()).hasSize(1);

            ValueObjectNode vo = cu.valueObjects().getFirst();
            assertThat(vo.name()).isEqualTo("Money");
            assertThat(vo.fields()).hasSize(2);
            assertThat(vo.validations()).hasSize(2);
            assertThat(vo.validations().getFirst().condition()).isEqualTo("amount >= 0");
            assertThat(vo.validations().getFirst().message()).isEqualTo("Amount must be positive");
        }

        @Test
        void shouldParseValueObjectWithExplicitCollectionAndMethods() {
            String src = """
                    namespace com.example.domain;
                    
                    value Money(BigDecimal amount, String? comment) validates {
                        require(amount.compareTo(BigDecimal.ZERO) >= 0, "Amount must be positive");
                    } list MoneyTransactions behavior {
                        public Money sum() {
                            return self.stream().reduce(Money.of(BigDecimal.ZERO), (a, b) -> Money.of(a.amount().add(b.amount())));
                        }
                    };
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.valueObjects()).hasSize(1);

            ValueObjectNode vo = cu.valueObjects().getFirst();
            assertThat(vo.name()).isEqualTo("Money");
            assertThat(vo.fields()).hasSize(2);
            assertThat(vo.fields().get(1).type().isOptional()).isTrue();

            assertThat(vo.collection()).isPresent();
            CollectionDefinitionNode coll = vo.collection().get();
            assertThat(coll.customName()).contains("MoneyTransactions");
            assertThat(coll.customMethods()).hasSize(1);
            assertThat(coll.customMethods().getFirst().name()).isEqualTo("sum");
            assertThat(coll.customMethods().getFirst().returnType().name()).isEqualTo("Money");
        }

        @Test
        @DisplayName("Parst Enum Value Object mit geordneten Konstanten und Methoden")
        void shouldParseEnumValueObject() {
            String src = """
                    namespace com.example.domain;
                    
                    value AcMode = ECO | COOL | HEAT | OFF behavior {
                        public boolean isActive() {
                            return self != AcMode.OFF;
                        }
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.valueObjects()).hasSize(1);

            ValueObjectNode vo = cu.valueObjects().getFirst();
            assertThat(vo.name()).isEqualTo("AcMode");
            assertThat(vo.isEnum()).isTrue();
            assertThat(vo.fields()).isEmpty();
            assertThat(vo.enumConstants()).hasSize(4);

            assertThat(vo.enumConstants()).extracting(EnumConstantNode::name)
                    .containsExactly("ECO", "COOL", "HEAT", "OFF");

            // Methode prüfen
            assertThat(vo.methods()).hasSize(1);
            assertThat(vo.methods().getFirst().name()).isEqualTo("isActive");
        }

        @Nested
        @DisplayName("10. Event Listeners")
        class ListenerTests {

            @Test
            @DisplayName("Parst Listener mit Dependencies und Statements")
            void shouldParseListener() {
                String src = """
                        namespace com.example.energy;
                        
                        synchronous listener Alert on StorageOverheated uses NotificationPort notifications behavior {
                            execute { self.notifications().sendAlert("Overheated"); }
                        }
                        """;

                CompilationUnitNode cu = parse(src);
                assertThat(cu.listeners()).hasSize(1);

                ListenerNode listener = cu.listeners().getFirst();
                assertThat(listener.eventType().name()).isEqualTo("StorageOverheated");
                assertThat(listener.listenerName()).isEqualTo("Alert");
                assertThat(listener.operation().injections()).hasSize(1);
                assertThat(listener.operation().injections().getFirst().type().name()).isEqualTo("NotificationPort");
                assertThat(listener.operation().injections().getFirst().name()).isEqualTo("notifications");
                assertThat(listener.operation().bodyCode()).contains("self.notifications()");
            }
        }
    }

    @Nested
    @DisplayName("2. Aggregates & Entities")
    class AggregateTests {

        @Test
        @DisplayName("Parst Aggregate mit Id-Header, mut-Feldern, Validierungen und Methoden")
        void shouldParseAggregateWithIdAndMethods() {
            String src = """
                    namespace com.example.domain;
                    
                    aggregate Project[ProjectId](ProjectName title, Money? budget, mut Tasks tasks) validates {
                        require(self.tasks().size() <= 100, "Max 100 tasks allowed");
                    } behavior {
                        modify TaskId addTask(TaskTitle title) {
                            TaskId newId = TaskId.create();
                            return newId;
                        }
                    };
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.aggregates()).hasSize(1);

            AggregateNode agg = cu.aggregates().getFirst();
            assertThat(agg.name()).isEqualTo("Project");
            assertThat(agg.idDefinition().type().name()).isEqualTo("ProjectId");
            assertThat(agg.idDefinition().fieldName()).isEqualTo("id");

            assertThat(agg.fields()).hasSize(3);

            FieldNode titleField = agg.fields().get(0);
            assertThat(titleField.name()).isEqualTo("title");
            assertThat(titleField.isMutable()).isFalse();

            FieldNode budgetField = agg.fields().get(1);
            assertThat(budgetField.name()).isEqualTo("budget");
            assertThat(budgetField.type().isOptional()).isTrue();
            assertThat(budgetField.isMutable()).isFalse();

            FieldNode tasksField = agg.fields().get(2);
            assertThat(tasksField.name()).isEqualTo("tasks");
            assertThat(tasksField.isMutable()).isTrue();

            assertThat(agg.validations()).hasSize(1);
            assertThat(agg.validations().getFirst().condition()).isEqualTo("self.tasks().size() <= 100");
            assertThat(agg.validations().getFirst().message()).isEqualTo("Max 100 tasks allowed");

            assertThat(agg.methods()).hasSize(1);
            MethodNode method = agg.methods().getFirst();
            assertThat(method.name()).isEqualTo("addTask");
            assertThat(method.parameters()).hasSize(1);
            assertThat(method.parameters().getFirst().name()).isEqualTo("title");
        }

        @Test
        @DisplayName("Parst Entity mit Id-Header und Feldern")
        void shouldParseEntityWithId() {
            String src = """
                    namespace com.example.domain;
                    
                    entity Task[TaskId](String title, mut int status);
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.entities()).hasSize(1);

            EntityNode entity = cu.entities().getFirst();
            assertThat(entity.name()).isEqualTo("Task");
            assertThat(entity.idDefinition().type().name()).isEqualTo("TaskId");
            assertThat(entity.idDefinition().fieldName()).isEqualTo("id");
            assertThat(entity.fields()).hasSize(2);
            assertThat(entity.fields().get(0).name()).isEqualTo("title");
            assertThat(entity.fields().get(1).name()).isEqualTo("status");
            assertThat(entity.fields().get(1).isMutable()).isTrue();
        }
    }

    @Nested
    @DisplayName("3. Events")
    class EventTests {

        @Test
        @DisplayName("Parses explicit recording independently of listeners")
        void shouldParseEventsWithDispatchKind() {
            String src = """
                    namespace com.example.domain;
                    
                    recorded event ProjectBudgetExceeded(ProjectId projectId, Money currentCost, Money budget);
                    event ProjectValidated(ProjectId projectId);
                    event TaskCompleted(ProjectId projectId, TaskId taskId);
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.events()).hasSize(3);

            EventNode outboxEvent = cu.events().get(0);
            assertThat(outboxEvent.name()).isEqualTo("ProjectBudgetExceeded");
            assertThat(outboxEvent.recorded()).isTrue();
            assertThat(outboxEvent.fields()).hasSize(3);

            EventNode memoryEvent = cu.events().get(1);
            assertThat(memoryEvent.name()).isEqualTo("ProjectValidated");
            assertThat(memoryEvent.recorded()).isFalse();

            EventNode defaultEvent = cu.events().get(2);
            assertThat(defaultEvent.name()).isEqualTo("TaskCompleted");
            assertThat(defaultEvent.recorded()).isFalse();
        }
    }

    @Nested
    @DisplayName("4. Repositories")
    class RepositoryTests {

        @Test
        @DisplayName("Derives repository names and parses declarative searches")
        void shouldParseRepositoryWithDefaultName() {
            String src = """
                    namespace com.example.domain;
                    
                    repository for Project {
                        find Projects findByName(ProjectName name) where name = :name;
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.repositories()).hasSize(1);

            RepositoryNode repo = cu.repositories().getFirst();
            assertThat(repo.name()).isEqualTo("ProjectRepository");
            assertThat(repo.aggregateName()).isEqualTo("Project");
            assertThat(repo.methods()).hasSize(1);
            assertThat(repo.methods().getFirst().name()).isEqualTo("findByName");
        }
    }

    @Nested
    @DisplayName("5. Ports and separate adapters")
    class PortTests {
        @Test void parsesDomainPortSignatures() {
            var unit=parse("namespace example; port Archive { Text? read(Text?); void store(Text); }");
            var port=unit.ports().getFirst();
            assertThat(port.methods()).hasSize(2);
            assertThat(port.methods().getFirst().returnType().isOptional()).isTrue();
            assertThat(port.methods().getFirst().parameters().getFirst().name()).isEqualTo("text");
        }
        @Test void parsesInlineAndExternalAdapterBehavior() {
            var unit=parse("""
                namespace example;
                adapter LocalArchive implements Archive uses Policy {
                    behavior {
                        java imports { java.util.Locale; }
                        java uses { java.lang.StringBuilder buffer; }
                        public Text read(Text) implemented by example.External;
                        public void store(Text) { self.buffer().append(text.string()); }
                    }
                }
                """);
            var adapter=(OutboundAdapterNode)unit.definitions().getFirst();
            assertThat(adapter.portType().name()).isEqualTo("Archive");
            assertThat(adapter.dependencies().getFirst().name()).isEqualTo("policy");
            assertThat(adapter.javaDependencies().getFirst().name()).isEqualTo("buffer");
            assertThat(adapter.methods().getFirst().method().implementation()).contains("example.External");
            assertThat(adapter.methods().get(1).method().bodyCode()).contains("self.buffer()");
        }
    }

    @Nested
    @DisplayName("6. Edge Cases & Contextual Keywords")
    class EdgeCaseTests {

        @Test
        @DisplayName("Erlaubt 'value' und 'id' in Feldnamen und Parametern")
        void shouldAllowValueAndIdAsVariableNames() {
            String src = """
                    namespace com.example.domain;
                    
                    value CustomType(String value, UUID id) validates {
                        require(value != null && id != null, "value and id missing");
                    } behavior {
                        public String combine(String id) {
                            return self.value() + id;
                        }
                    };
                    """;

            CompilationUnitNode cu = parse(src);
            ValueObjectNode vo = cu.valueObjects().getFirst();

            // Felder prüfen
            assertThat(vo.fields()).hasSize(2);
            assertThat(vo.fields().get(0).name()).isEqualTo("value");
            assertThat(vo.fields().get(1).name()).isEqualTo("id");

            // Methoden prüfen
            assertThat(vo.methods()).hasSize(1);
            assertThat(vo.methods().getFirst().name()).isEqualTo("combine");
            assertThat(vo.methods().getFirst().parameters().getFirst().name()).isEqualTo("id");

            // Expression prüfen
            assertThat(vo.validations().getFirst().condition()).isEqualTo("value != null && id != null");
        }
    }

    @Nested
    @DisplayName("7. Primitive Types Fallbacks")
    class PrimitiveFallbackTests {

        @Test
        @DisplayName("Leitet sichere Feldnamen für primitive Typen ab, um Keyword-Kollisionen zu vermeiden")
        void shouldDeriveSafeFieldNamesForPrimitives() {
            String src = """
                    namespace com.example.domain;
                    
                    value Metrics(int, boolean, long, double, byte);
                    """;

            CompilationUnitNode cu = parse(src);
            ValueObjectNode vo = cu.valueObjects().getFirst();

            assertThat(vo.fields()).hasSize(5);
            assertThat(vo.fields().get(0).name()).isEqualTo("intValue");
            assertThat(vo.fields().get(1).name()).isEqualTo("booleanValue");
            assertThat(vo.fields().get(2).name()).isEqualTo("longValue");
            assertThat(vo.fields().get(3).name()).isEqualTo("doubleValue");
            assertThat(vo.fields().get(4).name()).isEqualTo("byteValue");
        }
    }

    @Nested
    @DisplayName("8. Use Cases")
    class UseCaseTests {
        @Test void shouldParseUseCaseWithConventionsAndStatements() {
            var u=parse("""
                namespace demo;
                usecase Rename(TourId, Title title) returns (TourId, Title)
                    uses TourRepository, NotificationPort notifications
                    validates { require(self.title().string().length() > 0, "Required"); }
                behavior { execute { return Result.of(tourId,title); } }
                """).useCases().getFirst();
            assertThat(u.parameters().getFirst().name()).isEqualTo("tourId");
            assertThat(u.injections().getFirst().name()).isEqualTo("tourRepository");
            assertThat(u.injections().get(1).name()).isEqualTo("notifications");
            assertThat(u.resultFields()).hasSize(2);
            assertThat(u.validations().getFirst().condition()).contains("self.title()");
            assertThat(u.bodyCode()).contains("return Result.of");
            assertThat(u.executeCount()).isEqualTo(1);
        }
        @Test void shouldParseExplicitSingleReturnAndExternalImplementation() {
            var u=parse("namespace demo; usecase Find(TourId) returns Title? behavior { execute implemented by custom.Find; }").useCases().getFirst();
            assertThat(u.resultType().orElseThrow().name()).isEqualTo("Title");
            assertThat(u.resultType().orElseThrow().isOptional()).isTrue();
            assertThat(u.implementation()).contains("custom.Find");
        }
    }

    @Nested
    @DisplayName("9. Domain Services")
    class DomainServiceTests {

        @Test
        @DisplayName("Parst DomainService mit Conventions, Parametern, Validierung und Rückgabetyp")
        void shouldParseDomainService() {
            String src = """
                    namespace com.example.domain;
                    service TariffCalculator uses PricingPolicy behavior {
                        public WattHours calculate(WattHours capacity, WattHours storedEnergy, WattHours)
                        validates { require(self.capacity().value() > 0, "Capacity must be positive"); } {
                            return capacity;
                        }
                    }
                    """;
            var service = parse(src).domainServices().getFirst();
            assertThat(service.name()).isEqualTo("TariffCalculator");
            assertThat(service.injections().getFirst().name()).isEqualTo("pricingPolicy");
            var operation = service.methods().getFirst();
            assertThat(operation.method().name()).isEqualTo("calculate");
            assertThat(operation.method().parameters()).extracting(FieldNode::name)
                    .containsExactly("capacity", "storedEnergy", "wattHours");
            assertThat(operation.method().returnType().name()).isEqualTo("WattHours");
            assertThat(operation.validations()).hasSize(1);
            assertThat(operation.validations().getFirst().condition()).contains("self.capacity().value()");
            assertThat(operation.method().bodyCode()).contains("return capacity;");
        }
    }
}
