// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.runtime.BehaviorContractException;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

public class BehaviorContractTest {
    private VernacCompilationResult generate(String model) {
        return new VernacCompiler().compileSource("namespace behavior.tests; " + model);
    }
    private InMemoryJavaCompiler.CompilationOutput compile(String model) {
        var output = InMemoryJavaCompiler.compile(generate(model));
        assertTrue(output.success(), output.diagnostics().toString());
        return output;
    }

    @Test void inlineBehaviorHasExplicitReceiverPrivateHelpersAndScopedImports() throws Exception {
        var output = compile("""
                value PersonName(String) behavior {
                    java imports { java.util.Locale; }
                    public String upper() { return self.string().toUpperCase(Locale.ROOT) + suffix(2); }
                    private String suffix(int count) { return "!".repeat(count); }
                    public String? alternative() { return Optional.empty(); }
                } list Names behavior {
                    public int total() { return self.size(); }
                };
                """);
        var name = output.loadClass("behavior.tests.domain.PersonName");
        var instance = name.getMethod("of", String.class).invoke(null, "Hans");
        assertEquals("HANS!!", name.getMethod("upper").invoke(instance));
        assertEquals(Optional.empty(), name.getMethod("alternative").invoke(instance));
        assertThrows(NoSuchMethodException.class, () -> name.getDeclaredMethod("suffix", int.class));
        var helper = output.loadClass("behavior.tests.domain.__VernacBehavior_PersonName");
        assertNull(helper.getEnclosingClass());
        assertTrue(java.lang.reflect.Modifier.isPrivate(helper.getDeclaredMethod("suffix", int.class).getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(helper.getModifiers()));
    }

    @Test void inlineCodeCannotReadPrivateStateOrBypassTheConstructor() {
        for (String expression : new String[]{"self.string", "new PersonName(\"Hans\").string()"}) {
            var output = InMemoryJavaCompiler.compile(generate(
                    "value PersonName(String) behavior { public String bad() { return " + expression + "; } };"));
            assertFalse(output.success());
            assertTrue(output.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ROOT).contains("private")));
        }
    }

    @Test void badNonNullAndOptionalResultsFailAtTheBehaviorBoundary() throws Exception {
        var output = compile("""
                value Name(String) behavior {
                    public String bad() { return null; }
                    public String? missing() { return null; }
                };
                """);
        var name = output.loadClass("behavior.tests.domain.Name");
        var instance = name.getMethod("of", String.class).invoke(null, "x");
        for (String method : new String[]{"bad", "missing"}) {
            var failure = assertThrows(InvocationTargetException.class, () -> name.getMethod(method).invoke(instance));
            assertInstanceOf(BehaviorContractException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("Name." + method));
        }
    }

    @Test void nativeValidationReadsTheCandidateAndRejectsByCode() throws Exception {
        var output = compile("""
                id BookingId;
                value Day(int);
                aggregate Booking[BookingId](mut Day start, mut Day finish) validates {
                    require self start intValue <= self finish intValue else InvertedDates.
                    require self start intValue == 0 || 12 / self start intValue >= 1 else OutsideWindow.
                } behavior {
                    modify void move(Day start, Day finish) { self.start(start); self.finish(finish); }
                };
                """);
        var day = output.loadClass("behavior.tests.domain.Day");
        var booking = output.loadClass("behavior.tests.domain.Booking");
        var zero = day.getMethod("of", int.class).invoke(null, 0);
        var two = day.getMethod("of", int.class).invoke(null, 2);
        var five = day.getMethod("of", int.class).invoke(null, 5);
        var instance = booking.getMethod("create", day, day).invoke(null, zero, two);
        booking.getMethod("move", day, day).invoke(instance, five, five);
        var failure = assertThrows(InvocationTargetException.class,
                () -> booking.getMethod("move", day, day).invoke(instance, five, two));
        var rejection = assertInstanceOf(org.vernac.runtime.NativeValidation.Rejection.class, failure.getCause());
        assertEquals("InvertedDates", rejection.code());
        assertSame(five, booking.getMethod("finish").invoke(instance));
    }

    @Test void nativeValidationRejectsUnknownFieldsAndNonBooleanPredicates() {
        for (var predicate : java.util.List.of("self missing == 0", "self intValue + 1",
                "self change: 1")) {
            var failure = assertThrows(SemanticValidationException.class, () -> generate("""
                    value Count(int) validates { require %s else InvalidCount. };
                    """.formatted(predicate)));
            assertEquals("<memory>", failure.diagnostics().getFirst().location().sourceName());
        }
    }

    @Test void nativeModifyAndReadRunThroughExistingJvmMethods() throws Exception {
        var model = """
                id BookingId;
                value Day(int);
                aggregate Booking[BookingId](mut Day start, mut Day finish) behavior {
                    modify int move(Day oldStart, Day nextStart, Day nextFinish) vernac {
                        require self start == oldStart else StaleStart.
                        self start = nextStart.
                        self finish = nextFinish.
                        require self start intValue <= self finish intValue else InvertedDates.
                        return self finish intValue - self start intValue.
                    }
                    read int duration(int offset) vernac {
                        return self finish intValue - self start intValue + offset.
                    }
                };
                """;
        var output = compile(model);
        var booking = output.loadClass("behavior.tests.domain.Booking");
        var day = output.loadClass("behavior.tests.domain.Day");
        var of = day.getMethod("of", int.class);
        var first = of.invoke(null, 1);
        var third = of.invoke(null, 3);
        var fifth = of.invoke(null, 5);
        var instance = booking.getMethod("create", day, day).invoke(null, first, of.invoke(null, 2));
        var move = booking.getMethod("move", day, day, day);
        assertEquals(2, move.invoke(instance, first, third, fifth));
        assertEquals(12, booking.getMethod("duration", int.class).invoke(instance, 10));
        var failure = assertThrows(InvocationTargetException.class, () -> move.invoke(instance, third, of.invoke(null, 8), of.invoke(null, 7)));
        assertEquals("InvertedDates",
                assertInstanceOf(org.vernac.runtime.NativeValidation.Rejection.class, failure.getCause()).code());
        assertSame(third, booking.getMethod("start").invoke(instance));
        assertSame(fifth, booking.getMethod("finish").invoke(instance));
        var invalid = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("read int duration(int offset) vernac {",
                        "read int duration(int offset) vernac { self start = offset.")));
        assertTrue(invalid.getMessage().contains("Only modify behaviors"));
    }

    @Test void nativeMatchSupportsNestedBindingsAndEvaluatesOnlyTheSelectedBranch() throws Exception {
        String model = """
                id ScheduleId;
                value Day(int);
                aggregate Schedule[ScheduleId](Day? first, Day? second) validates {
                    require match self first {
                        present day => day intValue > 0
                        absent => true
                    } else InvalidFirstDay.
                } behavior {
                    read int combined(int fallback) vernac {
                        return match self first {
                            present firstDay => match self second {
                                present secondDay => firstDay intValue + secondDay intValue
                                absent => firstDay intValue + fallback
                            }
                            absent => 10 / fallback
                        }.
                    }
                };
                """;
        var output = compile(model);
        var day = output.loadClass("behavior.tests.domain.Day");
        var schedule = output.loadClass("behavior.tests.domain.Schedule");
        var create = schedule.getMethod("create", day, day);
        var combined = schedule.getMethod("combined", int.class);
        var four = day.getMethod("of", int.class).invoke(null, 4);
        var six = day.getMethod("of", int.class).invoke(null, 6);
        assertEquals(10, combined.invoke(create.invoke(null, four, six), 0));
        assertEquals(7, combined.invoke(create.invoke(null, four, null), 3));
        assertEquals(5, combined.invoke(create.invoke(null, null, six), 2));

        var incompatible = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("10 / fallback", "false")));
        assertTrue(incompatible.getMessage().contains("Match alternatives must have the same type"));
        var outOfScope = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("10 / fallback", "firstDay intValue")));
        assertTrue(outOfScope.getMessage().contains("Unknown native binding 'firstDay'"));
    }

    @Test void nativeKeywordMessagesCallReadBehaviorsInRulesAndBodies() throws Exception {
        String model = """
                id ReservationId;
                value Seats(int);
                aggregate Reservation[ReservationId](mut Seats booked) validates {
                    require (self totalWith: 0 factor: 1) <= 9 else TooManySeats.
                } behavior {
                    read int totalWith(int extra, int factor) vernac {
                        return (self booked intValue + extra) * factor.
                    }
                    read int withBonus(int factor) vernac {
                        return self totalWith: 1 factor: factor.
                    }
                    modify void changeTo(Seats seats) vernac {
                        self setTo: seats.
                    }
                    modify void setTo(Seats seats) vernac {
                        self booked = seats.
                    }
                };
                """;
        var output = compile(model);
        var seats = output.loadClass("behavior.tests.domain.Seats");
        var reservation = output.loadClass("behavior.tests.domain.Reservation");
        var four = seats.getMethod("of", int.class).invoke(null, 4);
        var ten = seats.getMethod("of", int.class).invoke(null, 10);
        var instance = reservation.getMethod("create", seats).invoke(null, four);
        assertEquals(10, reservation.getMethod("withBonus", int.class).invoke(instance, 2));
        var failure = assertThrows(InvocationTargetException.class,
                () -> reservation.getMethod("changeTo", seats).invoke(instance, ten));
        assertEquals("TooManySeats",
                assertInstanceOf(org.vernac.runtime.NativeValidation.Rejection.class, failure.getCause()).code());
        assertSame(four, reservation.getMethod("booked").invoke(instance));
        var eight = seats.getMethod("of", int.class).invoke(null, 8);
        reservation.getMethod("changeTo", seats).invoke(instance, eight);
        assertSame(eight, reservation.getMethod("booked").invoke(instance));

        var badSend = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("self totalWith: 1 factor: factor", "self changeTo: self booked")));
        assertTrue(badSend.getMessage().contains("readable behavior for selector 'changeTo:'"));
    }

    @Test void nativeExecuteChecksPortAndEventContractsBeforeGeneration() {
        String model = """
                id ItemId;
                value Title(String);
                port Inventory { void remove(ItemId); }
                event Removed(ItemId);
                usecase Remove(ItemId, Title) uses Inventory behavior {
                    execute vernac {
                        inventory remove: itemId.
                        events emit: (Removed itemId: itemId).
                    }
                }
                """;
        assertFalse(generate(model).generatedFiles().isEmpty());

        var badPort = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("remove: itemId", "remove: title")));
        assertTrue(badPort.getMessage().contains("Expression does not match"));
        var wrongEventField = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("Removed itemId:", "Removed title:")));
        assertTrue(wrongEventField.getMessage().contains("Event selector must name each declared field in order"));
        var notAnEvent = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("(Removed itemId: itemId)", "itemId")));
        assertTrue(notAnEvent.getMessage().contains("Expected a Vernac event"));
    }

    @Test void nativeUseCaseLoadsChangesAndSavesAnAggregateWithACompoundResult() throws Exception {
        String model = """
                id BookingId;
                value Day(int);
                aggregate Booking[BookingId](mut Day start, mut Day finish) behavior {
                    modify void moveFrom(Day previous, Day to) vernac {
                        require self start == previous else StaleBooking.
                        self start = to.
                        require self start intValue <= self finish intValue else InvertedDates.
                    }
                };
                repository Bookings for Booking {}
                usecase Move(BookingId, Day previous, Day destination)
                        returns (Day oldStart, Day newStart) uses Bookings behavior {
                    execute vernac {
                        let booking = bookings byId: bookingId.
                        let oldStart = booking start.
                        booking moveFrom: previous to: destination.
                        bookings save: booking.
                        return Result oldStart: oldStart newStart: booking start.
                    }
                }
                """;
        var generated = generate(model).generatedFiles().stream()
                .filter(file -> !file.packageName().endsWith(".adapter.outbound.jdbc")).toList();
        var output = InMemoryJavaCompiler.compile(generated);
        assertTrue(output.success(), output.diagnostics().toString());
        var bookingType = output.loadClass("behavior.tests.domain.Booking");
        var idType = output.loadClass("behavior.tests.domain.BookingId");
        var dayType = output.loadClass("behavior.tests.domain.Day");
        var portType = output.loadClass("behavior.tests.domain.Bookings");
        var useCaseType = output.loadClass("behavior.tests.usecase.Move");
        var first = dayType.getMethod("of", int.class).invoke(null, 1);
        var next = dayType.getMethod("of", int.class).invoke(null, 2);
        var end = dayType.getMethod("of", int.class).invoke(null, 3);
        var booking = bookingType.getMethod("create", dayType, dayType).invoke(null, first, end);
        var id = bookingType.getMethod("id").invoke(booking);
        var calls = new java.util.ArrayList<String>();
        var port = java.lang.reflect.Proxy.newProxyInstance(output.classLoader(), new Class<?>[]{portType},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "byId" -> {
                        assertSame(id, arguments[0]);
                        calls.add("byId");
                        yield booking;
                    }
                    case "save" -> {
                        assertSame(booking, arguments[0]);
                        calls.add("save");
                        yield booking;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var handlers = new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(org.vernac.runtime.events.EventHandler.class);
        var useCase = useCaseType.getConstructor(org.springframework.beans.factory.ObjectProvider.class, portType)
                .newInstance(handlers, port);
        var execute = useCaseType.getMethod("execute", idType, dayType, dayType);
        var result = execute.invoke(useCase, id, first, next);
        assertSame(first, result.getClass().getMethod("oldStart").invoke(result));
        assertSame(next, result.getClass().getMethod("newStart").invoke(result));
        var rejected = assertThrows(InvocationTargetException.class, () -> execute.invoke(useCase, id, first, end));
        assertEquals("StaleBooking",
                assertInstanceOf(org.vernac.runtime.NativeValidation.Rejection.class, rejected.getCause()).code());
        assertEquals(java.util.List.of("byId", "save", "byId"), calls);
        assertSame(next, bookingType.getMethod("start").invoke(booking));

        var duplicate = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("let oldStart", "let booking")));
        assertTrue(duplicate.getMessage().contains("duplicate native binding"));
        var wrongResult = assertThrows(SemanticValidationException.class,
                () -> generate(model.replace("Result oldStart:", "Result oldDay:")));
        assertTrue(wrongResult.getMessage().contains("Result selector must name each declared field"));
    }

    @Test void javaImportsCannotReplaceModelOrBuiltinTypes() {
        for (String model : new String[]{
                "value Name(String); value Other(String) behavior { java imports { foreign.Name; } };",
                "value Name(String) behavior { java imports { foreign.String; } };",
                "value Name(String) behavior { java imports { java.util.Date; java.sql.Date; } };"}) {
            var failure = assertThrows(SemanticValidationException.class, () -> generate(model));
            assertTrue(failure.getMessage().contains("conflicts with"));
        }
    }

    @Test void rejectsInvalidBehaviorDeclarationsBeforeJavac() {
        for (String model : new String[]{
                "value Name(String) behavior { protected String bad() { return self.string(); } };",
                "value Name(String) behavior { public String bad(String self) { return self; } };",
                "value Name(String) behavior { public @Nullable String bad() { return null; } };",
                "value Name(String) behavior { java imports { java.util.*; } };",
                "value Name(String) behavior { private String bad() implemented by x.Impl; };",
                "value Name(String) behavior { public String bad() implemented by x..Impl; };"})
            assertThrows(SemanticValidationException.class, () -> generate(model));
    }

    @Test void bodyOnlyModelReferencesAreImportedAcrossFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("model"));
        java.nio.file.Files.createDirectories(root.resolve("view"));
        java.nio.file.Files.writeString(root.resolve("model/name.vernac"), "namespace model; value Name(String);");
        java.nio.file.Files.writeString(root.resolve("view/text.vernac"), """
                namespace view;
                import model.Name;
                value Text(String) behavior {
                    public String echo() { return Name.of(self.string()).string(); }
                    public String choose(String? alternative) {
                        return alternative == null ? self.string() : alternative;
                    }
                };
                """);
        var output = InMemoryJavaCompiler.compile(new VernacCompiler().compileProject(root).generatedFiles());
        assertTrue(output.success(), output.diagnostics().toString());
        var type = output.loadClass("view.domain.Text");
        var instance = type.getMethod("of", String.class).invoke(null, "hello");
        assertEquals("hello", type.getMethod("echo").invoke(instance));
        assertEquals("hello", type.getMethod("choose", String.class).invoke(instance, (Object) null));
    }

    @Test void missingExternalImplementationFailsJavaCompilation() {
        var output = InMemoryJavaCompiler.compile(generate(
                "value Name(String) behavior { public int length() implemented by missing.Implementation; };"));
        assertFalse(output.success());
    }

    @Test void rejectsLoweredHelperCollisionAndKnownRepositoryImport() {
        var lowered = assertThrows(SemanticValidationException.class, () -> generate("""
                value Name(String) behavior {
                    public String label(String suffix) { return suffix; }
                    private String label(Name receiver, String suffix) { return suffix; }
                };
                """));
        assertTrue(lowered.getMessage().contains("after adding the self receiver"));
        var dependency = assertThrows(SemanticValidationException.class, () -> generate("""
                id AccountId;
                aggregate Account[AccountId]();
                repository Accounts for Account {}
                value Name(String) behavior {
                    java imports { behavior.tests.domain.Accounts; }
                };
                """));
        assertTrue(dependency.getMessage().contains("known repository or adapter"), dependency.getMessage());
    }

    @Test void domainBehaviorCannotImportTheGeneratedJdbcAdapter() {
        var error = assertThrows(SemanticValidationException.class, () -> generate("""
                id AccountId; aggregate Account[AccountId]();
                repository Accounts for Account {}
                value Name(String) behavior {
                    java imports { behavior.tests.adapter.outbound.jdbc.JdbcAccounts; }
                };
                """));
        assertTrue(error.getMessage().contains("known repository or adapter"), error.getMessage());
    }

    @Test void externalStaticImplementationIsCalledWithSelf() throws Exception {
        var output = compile("""
                value Name(String) behavior {
                    public int length() implemented by org.vernac.compiler.pipeline.BehaviorContractTest.Implementation;
                };
                """);
        var name = output.loadClass("behavior.tests.domain.Name");
        var instance = name.getMethod("of", String.class).invoke(null, "Hans");
        assertEquals(4, name.getMethod("length").invoke(instance));
    }

    @Test void generatedSourceLocationsAreIndependentOfCheckoutDirectory(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var roots = java.util.List.of(directory.resolve("first/src/main/vernac"),
                directory.resolve("second/src/main/vernac"));
        var outputs = new java.util.ArrayList<String>();
        for (var root : roots) {
            var source = root.resolve("behavior/tests/näme.vernac");
            java.nio.file.Files.createDirectories(source.getParent());
            java.nio.file.Files.writeString(source, """
                    namespace behavior.tests;
                    value Name(String) behavior {
                        public String label() { return self.string(); }
                        private String suffix() { return "!"; }
                    };
                    """);
            var files = new VernacCompiler().compileProject(root).generatedFiles();
            String generated = files.stream().map(Object::toString)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(generated.contains(directory.toString()));
            assertTrue(generated.contains("Name.label (behavior/tests/näme.vernac:3:5)"), generated);
            assertTrue(generated.contains("Vernac source: behavior/tests/näme.vernac:3:5"), generated);
            assertTrue(generated.contains("Vernac source: behavior/tests/näme.vernac:4:5"), generated);
            outputs.add(generated);
        }
        assertEquals(outputs.get(0), outputs.get(1));
    }

    @Test void inMemoryBehaviorPreservesItsSyntheticSourceName() {
        String generated = generate("value Name(String) behavior { public String label() { return self.string(); } };")
                .generatedFiles().stream().map(Object::toString)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(generated.contains("<memory>:"));
    }

    public static final class Implementation {
        public static int length(Object self) {
            try { return ((String) self.getClass().getMethod("string").invoke(self)).length(); }
            catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
        }
    }
}
