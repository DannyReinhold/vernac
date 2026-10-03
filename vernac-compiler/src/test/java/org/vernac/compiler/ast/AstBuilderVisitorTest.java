package org.vernac.compiler.ast;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;
import org.vernac.runtime.DispatchMode;

import static org.assertj.core.api.Assertions.assertThat;

class AstBuilderVisitorTest {

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Parst Aggregate mit Standard-Id und abgeleiteten sowie expliziten Feldnamen")
    void shouldParseAggregateWithIdAndMutableFields() {
        String src = """
                package com.example.domain;
                
                aggregate Project[ProjectId](ProjectName, mut Money budget) validates {
                    require(budget.amount().compareTo(BigDecimal.ZERO) >= 0, "Budget cannot be negative");
                } {
                    public void assignBudget(Money newBudget) {
                        budget(newBudget);
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
        assertThat(project.validations().getFirst().condition()).isEqualTo("budget.amount().compareTo(BigDecimal.ZERO)>=0");
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
                    package com.example.domain;
                    
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
        @DisplayName("Leitet bei Single Value Objects den Feldnamen 'value' automatisch ab")
        void shouldDeriveDefaultValueFieldNameForSingleParam() {
            String src = """
                    package com.example.domain;
                    value ProjectId(UUID);
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.valueObjects()).hasSize(1);

            ValueObjectNode vo = cu.valueObjects().getFirst();
            assertThat(vo.name()).isEqualTo("ProjectId");
            assertThat(vo.fields()).hasSize(1);
            assertThat(vo.fields().getFirst().name()).isEqualTo("value");
            assertThat(vo.fields().getFirst().type().name()).isEqualTo("UUID");
        }

        @Test
        @DisplayName("Unterstützt package-Override im Value Object Block")
        void shouldParseValueObjectWithPackageOverride() {
            String src = """
                    package com.example.domain;
                    value SharedId(UUID) {
                        package com.example.shared.kernel;
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            ValueObjectNode vo = cu.valueObjects().getFirst();
            assertThat(vo.customPackage()).contains("com.example.shared.kernel");
            assertThat(vo.fields().getFirst().name()).isEqualTo("value");
        }

        @Test
        void shouldParseValueObjectWithValidation() {
            String src = """
                    package com.example.domain;
                    
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
            assertThat(vo.validations().getFirst().condition()).isEqualTo("amount>=0");
            assertThat(vo.validations().getFirst().message()).isEqualTo("Amount must be positive");
        }

        @Test
        void shouldParseValueObjectWithExplicitCollectionAndMethods() {
            String src = """
                    package com.example.domain;
                    
                    value Money(BigDecimal amount, String? comment) validates {
                        require(amount.compareTo(BigDecimal.ZERO) >= 0, "Amount must be positive");
                    } collection MoneyTransactions {
                        public Money sum() {
                            return items.stream().reduce(Money.of(BigDecimal.ZERO), (a, b) -> Money.of(a.amount().add(b.amount())));
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
    }

    @Nested
    @DisplayName("2. Aggregates & Entities")
    class AggregateTests {

        @Test
        @DisplayName("Parst Aggregate mit Id-Header, mut-Feldern, Validierungen und Methoden")
        void shouldParseAggregateWithIdAndMethods() {
            String src = """
                    package com.example.domain;
                    
                    aggregate Project[ProjectId](ProjectName title, Money? budget, mut Tasks tasks) validates {
                        require(tasks.size() <= 100, "Max 100 tasks allowed");
                    } {
                        public TaskId addTask(TaskTitle title) {
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
            assertThat(agg.validations().getFirst().condition()).isEqualTo("tasks.size()<=100");
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
                    package com.example.domain;
                    
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
        @DisplayName("Parst outbox, memory und unpräfigierte Events mit DispatchMode")
        void shouldParseEventsWithDispatchKind() {
            String src = """
                    package com.example.domain;
                    
                    outbox event ProjectBudgetExceeded(ProjectId projectId, Money currentCost, Money budget);
                    memory event ProjectValidated(ProjectId projectId);
                    event TaskCompleted(ProjectId projectId, TaskId taskId);
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.events()).hasSize(3);

            EventNode outboxEvent = cu.events().get(0);
            assertThat(outboxEvent.name()).isEqualTo("ProjectBudgetExceeded");
            assertThat(outboxEvent.dispatchMode()).isEqualTo(DispatchMode.OUTBOX);
            assertThat(outboxEvent.fields()).hasSize(3);

            EventNode memoryEvent = cu.events().get(1);
            assertThat(memoryEvent.name()).isEqualTo("ProjectValidated");
            assertThat(memoryEvent.dispatchMode()).isEqualTo(DispatchMode.MEMORY);

            EventNode defaultEvent = cu.events().get(2);
            assertThat(defaultEvent.name()).isEqualTo("TaskCompleted");
            assertThat(defaultEvent.dispatchMode()).isEqualTo(DispatchMode.OUTBOX);
        }
    }

    @Nested
    @DisplayName("4. Repositories")
    class RepositoryTests {

        @Test
        @DisplayName("Leitet Standard-Namen ab und liest package-Override aus")
        void shouldParseRepositoryWithDefaultNameAndCustomPackage() {
            String src = """
                    package com.example.domain;
                    
                    repository for Project {
                        package com.example.infrastructure.own;
                        find List<Project> findByName(ProjectName name);
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.repositories()).hasSize(1);

            RepositoryNode repo = cu.repositories().getFirst();
            assertThat(repo.name()).isEqualTo("ProjectRepository");
            assertThat(repo.aggregateName()).isEqualTo("Project");
            assertThat(repo.customPackage()).contains("com.example.infrastructure.own");
            assertThat(repo.findMethods()).hasSize(1);
            assertThat(repo.findMethods().getFirst().name()).isEqualTo("findByName");
        }
    }

    @Nested
    @DisplayName("5. Ports (Outbound Adapters & Mappings)")
    class PortTests {

        @Test
        @DisplayName("Parst Port mit Schema, REST Adapter, Config, Error Handling und Mapping")
        void shouldParsePortWithRestAdapterAndMapping() {
            String src = """
                    package com.example.domain;
                    
                    port HolidayCalendarProvider {
                    
                        schema HolidayResponseDto {
                            LocalDate date;
                            String name;
                        }
                    
                        Optional<HolidayCalendar> loadCalendar(CountryCode country, Year year) throws ServiceUnavailableException {
                            adapter rest {
                                GET "/api/v1/holidays/{country}/{year}";
                                accept: "application/json";
                    
                                on 404 return Optional.empty();
                                on 401 return HolidayCalendarAccessNotAllowed.of(year, country);
                                on 5xx throw ExternalServiceException;
                            }
                            mapping {
                                response.body[*].date -> HolidayCalendar.holidays;
                                country -> HolidayCalendar.country;
                                year -> HolidayCalendar.year;
                            }
                        }
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.ports()).hasSize(1);

            PortNode port = cu.ports().getFirst();
            assertThat(port.name()).isEqualTo("HolidayCalendarProvider");

            assertThat(port.schemas()).hasSize(1);
            SchemaNode schema = port.schemas().getFirst();
            assertThat(schema.name()).isEqualTo("HolidayResponseDto");
            assertThat(schema.fields()).hasSize(2);
            assertThat(schema.fields().getFirst().type().name()).isEqualTo("LocalDate");
            assertThat(schema.fields().getFirst().name()).isEqualTo("date");

            assertThat(port.methods()).hasSize(1);
            PortMethodNode method = port.methods().getFirst();
            assertThat(method.name()).isEqualTo("loadCalendar");
            assertThat(method.returnType().name()).isEqualTo("Optional");
            assertThat(method.parameters()).hasSize(2);
            assertThat(method.thrownExceptions()).contains("ServiceUnavailableException");

            assertThat(method.adapter()).isInstanceOf(RestAdapterNode.class);
            RestAdapterNode restAdapter = (RestAdapterNode) method.adapter();

            assertThat(restAdapter.configs()).hasSize(2);
            assertThat(restAdapter.configs().get(0).key()).isEqualTo("GET");
            assertThat(restAdapter.configs().get(0).value()).isEqualTo("/api/v1/holidays/{country}/{year}");
            assertThat(restAdapter.configs().get(1).key()).isEqualTo("accept");
            assertThat(restAdapter.configs().get(1).value()).isEqualTo("application/json");

            assertThat(restAdapter.errorRules()).hasSize(3);
            assertThat(restAdapter.errorRules().get(0).statusCode()).isEqualTo("404");
            assertThat(restAdapter.errorRules().get(0).returnExpression()).contains("Optional.empty()");
            assertThat(restAdapter.errorRules().get(2).statusCode()).isEqualTo("5xx");
            assertThat(restAdapter.errorRules().get(2).throwExceptionType()).contains("ExternalServiceException");

            assertThat(method.mapping()).isPresent();
            MappingBlockNode mapping = method.mapping().get();
            assertThat(mapping.statements()).hasSize(3);
            assertThat(mapping.statements().get(0).sourcePath()).isEqualTo("response.body[*].date");
            assertThat(mapping.statements().get(0).targetPath()).isEqualTo("HolidayCalendar.holidays");
            assertThat(mapping.statements().get(0).direction()).isEqualTo("->");
        }

        @Test
        @DisplayName("Parst Port mit Custom Adaptern (Delegate und Inline Java)")
        void shouldParsePortWithCustomAdapters() {
            String src = """
                    package com.example.domain;
                    
                    port InvoiceGenerator {
                        PdfDocument generate(InvoiceData data) {
                            adapter custom InvoiceGeneratorDelegate;
                        }
                    
                        LegacyId syncCustomer(CustomerId id) {
                            adapter custom {
                                return LegacyId.of(id.value());
                            }
                        }
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            PortNode port = cu.ports().getFirst();
            assertThat(port.methods()).hasSize(2);

            // 1. Delegate Custom Adapter
            PortMethodNode delegateMethod = port.methods().get(0);
            assertThat(delegateMethod.adapter()).isInstanceOf(CustomAdapterNode.class);
            CustomAdapterNode delegateAdapter = (CustomAdapterNode) delegateMethod.adapter();
            assertThat(delegateAdapter.delegateName()).contains("InvoiceGeneratorDelegate");
            assertThat(delegateAdapter.inlineCode()).isEmpty();

            // 2. Inline Custom Adapter
            PortMethodNode inlineMethod = port.methods().get(1);
            assertThat(inlineMethod.adapter()).isInstanceOf(CustomAdapterNode.class);
            CustomAdapterNode inlineAdapter = (CustomAdapterNode) inlineMethod.adapter();
            assertThat(inlineAdapter.delegateName()).isEmpty();
            assertThat(inlineAdapter.inlineCode().get().trim()).contains("return LegacyId.of(id.value());");
        }
    }

    @Nested
    @DisplayName("6. Edge Cases & Contextual Keywords")
    class EdgeCaseTests {

        @Test
        @DisplayName("Erlaubt 'value' und 'id' in Feldnamen und Parametern, aber nicht als Methodenname")
        void shouldAllowValueAndIdAsVariableNames() {
            String src = """
                    package com.example.domain;
                    
                    value CustomType(String value, UUID id) validates {
                        require(value != null && id != null, "value and id missing");
                    } {
                        public String combine(String id) {
                            return this.value + id;
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
            assertThat(vo.validations().getFirst().condition()).isEqualTo("value!=null&&id!=null");
        }
    }

    @Nested
    @DisplayName("7. Primitive Types Fallbacks")
    class PrimitiveFallbackTests {

        @Test
        @DisplayName("Leitet sichere Feldnamen für primitive Typen ab, um Keyword-Kollisionen zu vermeiden")
        void shouldDeriveSafeFieldNamesForPrimitives() {
            String src = """
                    package com.example.domain;
                    
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

        @Test
        @DisplayName("Parst UseCase mit Conventions, Validierung, Java-Fragment und Tuple-Return")
        void shouldParseUseCaseWithConventionsAndStatements() {
            String src = """
                    package com.example.application;
                    
                    usecase CancelOrder(OrderId, String reason) validates {
                        require(!reason.isBlank(), "Reason must not be blank");
                    } {
                        use OrderRepository;
                        use NotificationPort notifications;
                    
                        load Order by orderId;
                    
                        if (order.isShipped()) {
                            throw new IllegalStateException("Already shipped");
                        }
                    
                        order.cancel(reason);
                        save order;
                    
                        return (order.id() as myOrderId, order.status());
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.useCases()).hasSize(1);

            UseCaseNode useCase = cu.useCases().getFirst();
            assertThat(useCase.name()).isEqualTo("CancelOrder");

            // Parameter
            assertThat(useCase.parameters()).hasSize(2);
            assertThat(useCase.parameters().get(0).name()).isEqualTo("orderId");
            assertThat(useCase.parameters().get(0).type().name()).isEqualTo("OrderId");
            assertThat(useCase.parameters().get(1).name()).isEqualTo("reason");

            // Validierung
            assertThat(useCase.validations()).hasSize(1);
            assertThat(useCase.validations().getFirst().condition()).isEqualTo("!reason.isBlank()");

            // Dependencies (use)
            assertThat(useCase.dependencies()).hasSize(2);
            assertThat(useCase.dependencies().get(0).typeName()).isEqualTo("OrderRepository");
            assertThat(useCase.dependencies().get(0).instanceName()).isEmpty(); // Greift Default
            assertThat(useCase.dependencies().get(1).typeName()).isEqualTo("NotificationPort");
            assertThat(useCase.dependencies().get(1).instanceName()).contains("notifications");

            // Statements
            assertThat(useCase.statements()).hasSize(4);

            // Statement 1: load
            assertThat(useCase.statements().get(0)).isInstanceOf(LoadStatementNode.class);
            LoadStatementNode loadStmt = (LoadStatementNode) useCase.statements().get(0);
            assertThat(loadStmt.aggregateType()).isEqualTo("Order");
            assertThat(loadStmt.idExpressionCode()).contains("orderId");

            // Statement 2: Java if-statement
            assertThat(useCase.statements().get(1)).isInstanceOf(RawJavaStatementNode.class);

            // Statement 3: order.cancel(reason); (geparst als RawJavaStatement)
            assertThat(useCase.statements().get(2)).isInstanceOf(RawJavaStatementNode.class);

            // Statement 4: save
            assertThat(useCase.statements().get(3)).isInstanceOf(SaveStatementNode.class);
            SaveStatementNode saveStmt = (SaveStatementNode) useCase.statements().get(3);
            assertThat(saveStmt.instanceName()).isEqualTo("order");

            // Return Statement (Tuple)
            assertThat(useCase.returnStatement()).isPresent();
            assertThat(useCase.returnStatement().get()).isInstanceOf(TupleReturnNode.class);
            TupleReturnNode tuple = (TupleReturnNode) useCase.returnStatement().get();
            assertThat(tuple.elements()).hasSize(2);
            assertThat(tuple.elements().get(0).expressionCode()).isEqualTo("order.id()");
            assertThat(tuple.elements().get(0).alias()).contains("myOrderId");
            assertThat(tuple.elements().get(1).expressionCode()).isEqualTo("order.status()");
            assertThat(tuple.elements().get(1).alias()).isEmpty();
        }

        @Test
        @DisplayName("Unterstützt package-Override und Single Return Statement")
        void shouldParseUseCaseWithCustomPackageAndSingleReturn() {
            String src = """
                    package com.example.application;
                    
                    usecase CreateOrder(CustomerId customerId) {
                        package com.example.mycustom.ordering;
                        use OrderRepository;
                    
                        save order;
                        return order.id();
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            UseCaseNode useCase = cu.useCases().getFirst();

            assertThat(useCase.customPackage()).contains("com.example.mycustom.ordering");
            assertThat(useCase.returnStatement()).isPresent();
            assertThat(useCase.returnStatement().get()).isInstanceOf(SingleReturnNode.class);
            SingleReturnNode single = (SingleReturnNode) useCase.returnStatement().get();
            assertThat(single.expressionCode()).contains("order.id()");
        }
    }
}