// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.VernacDomainException;
import org.vernac.runtime.VernacException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import org.vernac.compiler.ast.AstBuilderVisitor;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.ValueObjectNode;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValueObjectGeneratorTest {


    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Generiert UUID-Value-Object mit Factories und benanntem Getter")
    void shouldGenerateSingleValueObjectWithHelpers() {
        String src = """
                namespace com.example.domain;
                value ProjectId(UUID value);
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src).generatedFiles().getFirst();
        String code = file.toString();

        assertThat(code)
                .contains("public final class ProjectId implements ValueObject")
                .contains("private final UUID value;")
                .contains("private ProjectId(UUID value)")
                .contains("throw new DomainValidationException(\"ProjectId.value must not be null\")")
                .contains("public static ProjectId of(UUID value)")
                .contains("public static ProjectId of(String value)")
                .contains("public static ProjectId create()")
                .contains("public UUID value()")
                .doesNotContain(" asString(")
                .doesNotContain(" asUuid(");
    }

    @Test
    @DisplayName("Generiert Multi-Value Object mit @Nullable und Optional Getter")
    void shouldGenerateMultiValueObjectWithNullableAndOptional() {
        String src = """
                namespace com.example.domain;
                value Money(BigDecimal amount, String? comment) validates {
                    require(self.amount().compareTo(BigDecimal.ZERO) >= 0, "Amount must be positive");
                };
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src).generatedFiles().getFirst();
        String code = file.toString();

        assertThat(code)
                .contains("public final class Money implements ValueObject")
                .contains("private final BigDecimal amount;")
                .contains("@Nullable")
                .contains("private final @Nullable String comment;")
                .contains("public Optional<String> comment()")
                .contains("return Optional.ofNullable(this.comment);")
                .contains("private void validate()")
                .contains("__VernacValidation_Money.validate(new __ReadView())")
                .contains("public static Money of(BigDecimal amount)")
                .contains("public static Money of(BigDecimal amount, @Nullable String comment)");
    }

    @Test
    @DisplayName("Generiert konsistent explizite Feldnamen (Felder, Konstruktor, Getter, Factories)")
    void shouldGenerateCodeWithExplicitFieldNames() {
        String src = """
                namespace com.example.domain;
                value UserEmail(String emailAddress, boolean isVerified);
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src).generatedFiles().getFirst();
        String code = file.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("private final String emailAddress;")
                .contains("private final boolean isVerified;")
                .contains("private UserEmail(String emailAddress, boolean isVerified)")
                .contains("DomainChecks.requireNonNull(emailAddress, \"UserEmail.emailAddress\")")
                .contains("public static UserEmail of(String emailAddress, boolean isVerified)")
                .contains("public String emailAddress() { return this.emailAddress; }")
                .contains("public boolean isVerified() { return this.isVerified; }");
    }

    @Test
    @DisplayName("Leitet Namen aus Typen ab, wenn mehrere Felder ohne Namen angegeben sind")
    void shouldDeriveFieldNamesFromTypes() {
        String src = """
                namespace com.example.domain;
                value Document(String, UUID);
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src).generatedFiles().getFirst();
        String code = file.toString().replaceAll("\\s+", " ");

        // String -> string, UUID -> uuid
        assertThat(code)
                .contains("private final String string;")
                .contains("private final UUID uuid;")
                .contains("private Document(String string, UUID uuid)")
                .contains("public static Document of(String string, UUID uuid)")
                .contains("public String string()")
                .contains("public UUID uuid()");
    }

    @Test
    @DisplayName("Generiert ein Java-Enum ohne Persistenzcodes und mit eigenen Methoden")
    void shouldGenerateEnumValueObject() {
        String src = """
                namespace com.example.climate;
                
                value AcMode = ECO | COOL | HEAT | OFF behavior {
                    public boolean isActive() {
                        return self != AcMode.OFF;
                    }
                }
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src).generatedFiles().getFirst();
        String code = file.toString();

        assertThat(code).contains("public enum AcMode implements ValueObject");
        assertThat(code).contains("ECO,", "COOL,", "HEAT,", "OFF;", "@NullMarked");
        assertThat(code).doesNotContain("dbValue", "static AcMode of(", "String toString()");
        assertThat(code).contains("public boolean isActive()");
    }
    @Nested
    class RuntimeContract {
        private static InMemoryJavaCompiler.CompilationOutput compiled;

        @BeforeAll
        static void compileContractExamples() {
            var result = new VernacCompiler().compileSource("""
                    namespace contract.values;
                    value Mixed(String required, Integer? count, String? note);
                    value Contact(String? email, String? phone);
                    value RequiredContact(String? email, String? phone) validates {
                        require(self.email().isPresent() || self.phone().isPresent(), "one contact required");
                    };
                    value NeedsEmail(String name, String? email) validates {
                        require(self.email().isPresent(), "email required");
                    };
                    value OptionalText(String? value);
                    value OptionalUuid(UUID? value);
                    value RequiredText(String value);
                    value RequiredUuid(UUID value);
                    value SameText(String value);
                    value Ordered(String first, String second) validates {
                        require(self.first().length() > 2, "first invariant");
                        require(self.second().length() > 2, "second invariant");
                    };
                    value Positive(int value) validates {
                        require(self.value() > 0, "positive required");
                        require(self.value() > 10, "over ten required");
                    };
                    value Explosive(String value) validates {
                        require(self.value().substring(10).isBlank(), "unused message");
                    };
                    value Amount(BigDecimal value);
                    value Floating(float first, double second);
                    value Nested(RequiredText title, Amount amount);
                    value Phase = PENDING | DONE;
                    value Behavior(String value) behavior {
                        private boolean hasValue(Behavior receiver) { return !receiver.value().isBlank(); }
                        public boolean useful() { return hasValue(self); }
                        public String priceLabel() { return "$5"; }
                        public void check() { if (!hasValue(self)) throw new IllegalStateException(); }
                        public String? maybe() { return java.util.Optional.of(self.value()); }
                    };
                    """);
            compiled = InMemoryJavaCompiler.compile(result);
            assertTrue(compiled.success(), compiled.diagnostics().toString());
        }

        private Class<?> type(String name) throws ClassNotFoundException {
            return compiled.loadClass("contract.values.domain." + name);
        }

        private Object of(String name, Class<?>[] parameters, Object... values) throws ReflectiveOperationException {
            return type(name).getMethod("of", parameters).invoke(null, values);
        }

        private Throwable rejected(String name, Class<?>[] parameters, Object... values) {
            return assertThrows(InvocationTargetException.class, () -> of(name, parameters, values)).getCause();
        }

        @Test
        void exposesExactlyTheFullAndRequiredOnlyFactories() throws Exception {
            var mixed = type("Mixed");
            assertEquals(2, java.util.Arrays.stream(mixed.getDeclaredMethods()).filter(m -> m.getName().equals("of")).count());
            assertNotNull(mixed.getMethod("of", String.class, Integer.class, String.class));
            assertNotNull(mixed.getMethod("of", String.class));
            assertThrows(NoSuchMethodException.class, () -> mixed.getMethod("of", String.class, Integer.class));
            var value = of("Mixed", new Class<?>[]{String.class}, "name");
            assertEquals(Optional.empty(), mixed.getMethod("count").invoke(value));
            assertEquals(Optional.empty(), mixed.getMethod("note").invoke(value));
        }

        @Test
        void allOptionalFieldsDoNotCreateAZeroArgumentFactory() throws Exception {
            assertThrows(NoSuchMethodException.class, () -> type("Contact").getMethod("of"));
            var contact = of("Contact", new Class<?>[]{String.class, String.class}, null, null);
            assertEquals(Optional.empty(), type("Contact").getMethod("email").invoke(contact));
            assertInstanceOf(DomainValidationException.class,
                    rejected("RequiredContact", new Class<?>[]{String.class, String.class}, null, null));
        }

        @Test
        void annotationsDescribeFieldsConstructorsFactoriesAndEquals() throws Exception {
            Class<?> mixed = type("Mixed");
            assertTrue(mixed.isAnnotationPresent(NullMarked.class));
            assertTrue(type("Phase").isAnnotationPresent(NullMarked.class));
            assertTrue(mixed.getDeclaredField("note").getAnnotatedType().isAnnotationPresent(Nullable.class));
            var factory = mixed.getMethod("of", String.class, Integer.class, String.class);
            assertFalse(factory.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
            assertTrue(factory.getAnnotatedParameterTypes()[1].isAnnotationPresent(Nullable.class));
            var constructor = mixed.getDeclaredConstructor(String.class, Integer.class, String.class);
            assertTrue(constructor.getAnnotatedParameterTypes()[2].isAnnotationPresent(Nullable.class));
            assertTrue(mixed.getMethod("equals", Object.class).getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
        }

        @Test
        void constructionAndStateArePrivateAndImmutable() throws Exception {
            var mixed = type("Mixed");
            assertTrue(Modifier.isFinal(mixed.getModifiers()));
            assertFalse(mixed.isRecord());
            assertEquals(0, mixed.getConstructors().length);
            for (var field : mixed.getDeclaredFields()) {
                assertTrue(Modifier.isPrivate(field.getModifiers()));
                assertTrue(Modifier.isFinal(field.getModifiers()));
            }
            assertThrows(NoSuchMethodException.class, () -> mixed.getMethod("setRequired", String.class));
            assertThrows(NoSuchMethodException.class, () -> mixed.getMethod("required", String.class));
        }

        @Test
        void requiredChecksRunInFieldOrderBeforeAnyInvariant() {
            var first = rejected("Ordered", new Class<?>[]{String.class, String.class}, null, null);
            assertInstanceOf(DomainValidationException.class, first);
            assertEquals("Ordered.first must not be null", first.getMessage());
            assertEquals("Ordered.second must not be null",
                    rejected("Ordered", new Class<?>[]{String.class, String.class}, "x", null).getMessage());
            assertEquals("Ordered: first invariant",
                    rejected("Ordered", new Class<?>[]{String.class, String.class}, "x", "y").getMessage());
            assertEquals("Ordered: second invariant",
                    rejected("Ordered", new Class<?>[]{String.class, String.class}, "valid", "y").getMessage());
            assertInstanceOf(VernacDomainException.class, first);
            assertInstanceOf(VernacException.class, first);
        }

        @Test
        void checksAllFactoriesAndPreservesUnexpectedValidationExceptions() {
            assertInstanceOf(DomainValidationException.class,
                    rejected("Mixed", new Class<?>[]{String.class}, (Object) null));
            assertEquals("Positive: positive required", rejected("Positive", new Class<?>[]{int.class}, -1).getMessage());
            assertEquals("Positive: over ten required", rejected("Positive", new Class<?>[]{int.class}, 5).getMessage());
            assertInstanceOf(StringIndexOutOfBoundsException.class,
                    rejected("Explosive", new Class<?>[]{String.class}, "x"));
            assertEquals("NeedsEmail: email required", rejected("NeedsEmail", new Class<?>[]{String.class}, "name").getMessage());
        }

        @Test
        void optionalGettersReplaceRedundantConvenienceAccessors() throws Exception {
            var text = of("OptionalText", new Class<?>[]{String.class}, (Object) null);
            assertEquals(Optional.empty(), type("OptionalText").getMethod("value").invoke(text));
            var empty = of("OptionalUuid", new Class<?>[]{UUID.class}, (Object) null);
            assertEquals(Optional.empty(), type("OptionalUuid").getMethod("value").invoke(empty));
            for (String name : List.of("OptionalText", "OptionalUuid", "RequiredUuid")) {
                assertThrows(NoSuchMethodException.class, () -> type(name).getMethod("asString"));
                assertThrows(NoSuchMethodException.class, () -> type(name).getMethod("asUuid"));
            }
            UUID uuid = UUID.randomUUID();
            var present = of("OptionalUuid", new Class<?>[]{String.class}, uuid.toString());
            assertEquals(Optional.of(uuid), type("OptionalUuid").getMethod("value").invoke(present));
            var parsedNull = of("OptionalUuid", new Class<?>[]{String.class}, (Object) null);
            assertEquals(Optional.empty(), type("OptionalUuid").getMethod("value").invoke(parsedNull));
        }

        @Test
        void uuidParsingPreservesTheCauseAndConstructionRules() {
            var malformed = rejected("RequiredUuid", new Class<?>[]{String.class}, "not-a-uuid");
            assertInstanceOf(DomainValidationException.class, malformed);
            assertInstanceOf(IllegalArgumentException.class, malformed.getCause());
            assertInstanceOf(DomainValidationException.class,
                    rejected("RequiredUuid", new Class<?>[]{UUID.class}, (Object) null));
        }

        @Test
        void equalityIncludesEveryFieldAndAbsence() throws Exception {
            var parameters = new Class<?>[]{String.class, Integer.class, String.class};
            var base = of("Mixed", parameters, "a", 1, "b");
            var same = of("Mixed", parameters, "a", 1, "b");
            assertEquals(base, same);
            assertEquals(base.hashCode(), same.hashCode());
            assertNotEquals(base, of("Mixed", parameters, "other", 1, "b"));
            assertNotEquals(base, of("Mixed", parameters, "a", 2, "b"));
            assertNotEquals(base, of("Mixed", parameters, "a", 1, "other"));
            assertNotEquals(base, of("Mixed", parameters, "a", null, "b"));
            assertFalse(base.equals(null));
            assertTrue(base.toString().contains("required='a'"));
            assertTrue(base.toString().contains("count='1'"));
            assertTrue(base.toString().contains("note='b'"));
        }

        @Test
        void equalityIsTypeBoundAndUsesNestedValueEquality() throws Exception {
            var title = of("RequiredText", new Class<?>[]{String.class}, "same");
            assertNotEquals(title, of("SameText", new Class<?>[]{String.class}, "same"));
            var amount = of("Amount", new Class<?>[]{BigDecimal.class}, new BigDecimal("1.00"));
            var nested = of("Nested", new Class<?>[]{type("RequiredText"), type("Amount")}, title, amount);
            var other = of("Nested", new Class<?>[]{type("RequiredText"), type("Amount")},
                    of("RequiredText", new Class<?>[]{String.class}, "same"),
                    of("Amount", new Class<?>[]{BigDecimal.class}, new BigDecimal("1.00")));
            assertEquals(nested, other);
            assertEquals(nested.hashCode(), other.hashCode());
        }

        @Test
        void preservesDecimalScaleFloatingPointEqualityAndOriginalStrings() throws Exception {
            assertNotEquals(of("Amount", new Class<?>[]{BigDecimal.class}, new BigDecimal("1.0")),
                    of("Amount", new Class<?>[]{BigDecimal.class}, new BigDecimal("1.00")));
            var signature = new Class<?>[]{float.class, double.class};
            var nan = of("Floating", signature, Float.NaN, Double.NaN);
            var otherNan = of("Floating", signature, Float.intBitsToFloat(0x7fc00001), Double.longBitsToDouble(0x7ff8000000000001L));
            assertEquals(nan, otherNan);
            assertEquals(nan.hashCode(), otherNan.hashCode());
            assertNotEquals(of("Floating", signature, 0.0f, 0.0d), of("Floating", signature, -0.0f, 0.0d));
            assertNotEquals(of("Floating", signature, 0.0f, 0.0d), of("Floating", signature, 0.0f, -0.0d));
            var text = of("RequiredText", new Class<?>[]{String.class}, " padded ");
            assertEquals(" padded ", type("RequiredText").getMethod("value").invoke(text));
        }

        @Test
        void customMethodSignaturesAndVisibilityUseTheSameResolvedTypes() throws Exception {
            var behavior = of("Behavior", new Class<?>[]{String.class}, "hello");
            assertEquals(true, type("Behavior").getMethod("useful").invoke(behavior));
            assertEquals("$5", type("Behavior").getMethod("priceLabel").invoke(behavior));
            assertThrows(NoSuchMethodException.class, () -> type("Behavior").getDeclaredMethod("hasValue"));
            assertEquals(Optional.of("hello"), type("Behavior").getMethod("maybe").invoke(behavior));
            type("Behavior").getMethod("check").invoke(behavior);
        }
    }

    @Test
    void diagnosesGeneratedApiCollisionsBeforeJavac() {
        for (String declaration : List.of(
                "value Example(String toString);",
                "value Example(UUID create);",
                "value Example(String text) behavior { public String text() { return text; } }",
                "value Example(String getClass);")) {
            var error = assertThrows(SemanticValidationException.class, () ->
                    new VernacCompiler().compileSource("namespace contract.collision; " + declaration));
            assertTrue(error.getMessage().contains("conflicts with"), error.getMessage());
        }
    }


    @Test
    void allowsExplicitConversionMethodsAndDelegatesRequiredOnlyFactory() throws Exception {
        var result = new VernacCompiler().compileSource("""
                namespace contract.explicit;
                value Text(String value) behavior {
                    public String asString() { return self.value(); }
                };
                value Uuid(UUID asString);
                value Note(String title, String? detail);
                """);
        var compiled = InMemoryJavaCompiler.compile(result);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        var text = compiled.loadClass("contract.explicit.domain.Text");
        var instance = text.getMethod("of", String.class).invoke(null, "hello");
        assertEquals("hello", text.getMethod("asString").invoke(instance));
        assertThat(result.generatedFiles().stream().filter(f -> f.typeSpec().name().equals("Note"))
                .findFirst().orElseThrow().toString()).contains("return Note.of(title, null);");
    }
}
