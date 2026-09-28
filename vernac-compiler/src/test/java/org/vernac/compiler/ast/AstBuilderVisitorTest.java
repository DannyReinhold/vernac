package org.vernac.compiler.ast;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import static org.assertj.core.api.Assertions.assertThat;

class AstBuilderVisitorTest {

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Nested
    @DisplayName("1. Value Objects")
    class ValueObjectTests {

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
        void shouldParseAggregateWithInvariantsAndChildEntity() {
            String src = """
                    aggregate Project {
                        id: ProjectId;
                        title: NonEmptyString;
                        budget: Money?;
                        tasks: List<Task>;
                    
                        invariant MaxTasksForDraft {
                            if (status == ProjectStatus.DRAFT) {
                                require(tasks.size() <= 3, "Draft max 3 tasks");
                            }
                        }
                    
                        entity Task {
                            id: TaskId;
                            title: NonEmptyString;
                            isCompleted: Boolean = false;
                    
                            internal void markDone() {
                                this.isCompleted = true;
                            }
                        }
                    
                        public TaskId addTask(NonEmptyString title) {
                            TaskId newId = new TaskId(UUID.randomUUID());
                            this.tasks.add(new Task(newId, title, false));
                            return newId;
                        }
                    }
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.aggregates()).hasSize(1);

            AggregateNode agg = cu.aggregates().getFirst();
            assertThat(agg.name()).isEqualTo("Project");
            assertThat(agg.fields()).hasSize(4);

            // Optional type check
            FieldNode budgetField = agg.fields().get(2);
            assertThat(budgetField.name()).isEqualTo("budget");
            assertThat(budgetField.type().isOptional()).isTrue();

            // Invariant check
            assertThat(agg.invariants()).hasSize(1);
            assertThat(agg.invariants().getFirst().name()).isEqualTo("MaxTasksForDraft");

            // Child entity check
            assertThat(agg.entities()).hasSize(1);
            EntityNode task = agg.entities().getFirst();
            assertThat(task.name()).isEqualTo("Task");
            assertThat(task.fields()).hasSize(3);
            assertThat(task.fields().get(2).defaultValue()).contains("false");
            assertThat(task.methods()).hasSize(1);
            assertThat(task.methods().getFirst().accessModifier()).isEqualTo("internal");

            // Aggregate root method check
            assertThat(agg.methods()).hasSize(1);
            MethodNode method = agg.methods().getFirst();
            assertThat(method.name()).isEqualTo("addTask");
            assertThat(method.parameters()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("3. Events")
    class EventTests {

        @Test
        void shouldParseAnnotatedDomainEvents() {
            String src = """
                    @Dispatch(Outbox)
                    event ProjectBudgetExceeded(ProjectId projectId, Money currentCost, Money budget);
                    
                    event TaskCompleted(ProjectId projectId, TaskId taskId);
                    """;

            CompilationUnitNode cu = parse(src);
            assertThat(cu.events()).hasSize(2);

            EventNode outboxEvent = cu.events().getFirst();
            assertThat(outboxEvent.name()).isEqualTo("ProjectBudgetExceeded");
            assertThat(outboxEvent.annotations()).hasSize(1);
            assertThat(outboxEvent.annotations().getFirst().name()).isEqualTo("Dispatch");
            assertThat(outboxEvent.annotations().getFirst().singleValue()).contains("Outbox");
            assertThat(outboxEvent.fields()).hasSize(3);
        }

        @Nested
        @DisplayName("4. Services (ACL Ports & Mappings)")
        class ServiceTests {

            @Test
            void shouldParseServiceWithExternalSchemaAndMapping() {
                String src = """
                        @HttpService(baseUrl = "${calendar.service.url}")
                        service HolidayCalendarService {
                        
                            external schema HolidayResponseItem {
                                date: LocalDate;
                                name: String;
                            }
                        
                            @Get("/api/v1/holidays/{country}/{year}")
                            HolidayCalendar loadCalendar(CountryCode country, Year year) throws ServiceUnavailableException mapping {
                                response.body[*].date -> HolidayCalendar.holidays;
                                country -> HolidayCalendar.country;
                                year -> HolidayCalendar.year;
                            }
                        }
                        """;

                CompilationUnitNode cu = parse(src);
                assertThat(cu.services()).hasSize(1);

                ServiceNode svc = cu.services().getFirst();
                assertThat(svc.name()).isEqualTo("HolidayCalendarService");

                // Annotation prüfen (baseUrl = "...")
                assertThat(svc.annotations()).hasSize(1);
                AnnotationNode annotation = svc.annotations().getFirst();
                assertThat(annotation.name()).isEqualTo("HttpService");
                assertThat(annotation.attributes()).containsEntry("baseUrl", "${calendar.service.url}");

                // Schema prüfen
                assertThat(svc.schemas()).hasSize(1);
                ExternalSchemaNode schema = svc.schemas().getFirst();
                assertThat(schema.name()).isEqualTo("HolidayResponseItem");
                assertThat(schema.fields()).containsKeys("date", "name");
                assertThat(schema.fields().get("date").name()).isEqualTo("LocalDate");
                assertThat(schema.fields().get("name").name()).isEqualTo("String");

                // Servicemethode & Mappings prüfen
                assertThat(svc.methods()).hasSize(1);
                ServiceMethodNode method = svc.methods().getFirst();
                assertThat(method.httpMethod()).isEqualTo("Get");
                assertThat(method.endpointPath()).isEqualTo("/api/v1/holidays/{country}/{year}");
                assertThat(method.returnType().name()).isEqualTo("HolidayCalendar");
                assertThat(method.parameters()).hasSize(2);
                assertThat(method.thrownExceptions()).contains("ServiceUnavailableException");

                assertThat(method.mappings()).hasSize(3);
                assertThat(method.mappings().get(0).sourceExpression()).isEqualTo("response.body[*].date");
                assertThat(method.mappings().get(0).targetField()).isEqualTo("HolidayCalendar.holidays");
                assertThat(method.mappings().get(1).sourceExpression()).isEqualTo("country");
                assertThat(method.mappings().get(1).targetField()).isEqualTo("HolidayCalendar.country");
                assertThat(method.mappings().get(2).sourceExpression()).isEqualTo("year");
                assertThat(method.mappings().get(2).targetField()).isEqualTo("HolidayCalendar.year");
            }
        }
    }
}