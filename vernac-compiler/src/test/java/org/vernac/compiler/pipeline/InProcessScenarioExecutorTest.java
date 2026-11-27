// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.runtime.NativeBehaviorSources;
import org.vernac.runtime.NativeValidation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcessScenarioExecutorTest {
    @TempDir Path root;

    @Test
    void junitDoesNotStartFurtherScenariosAfterCancellation() throws Exception {
        var sources = new VersionedSources(root, Map.of());
        var planning = new ScenarioPlanningSession(root, Map.of(), List.of());
        var plan = planning.plan(Optional.empty(),
                new ScenarioPlanningSession.Inputs(sources.snapshot(), Map.of()), false, null);
        var signal = root.resolve("cancelled");
        var resume = root.resolve("resume");
        var first = MethodSpec.methodBuilder("a")
                .addAnnotation(Test.class).addException(Exception.class)
                .addStatement("$T.createFile($T.of($S))", Files.class, Path.class, signal.toString())
                .addStatement("long deadline = System.nanoTime() + 5_000_000_000L")
                .beginControlFlow("while ($T.notExists($T.of($S)) && System.nanoTime() < deadline)",
                        Files.class, Path.class, resume.toString())
                .addStatement("Thread.sleep(10)").endControlFlow()
                .addStatement("$T.assertTrue($T.exists($T.of($S)))", Assertions.class, Files.class, Path.class,
                        resume.toString()).build();
        var second = MethodSpec.methodBuilder("b").addAnnotation(Test.class)
                .addStatement("$T.fail($S)", Assertions.class, "Cancelled test should not start").build();
        var suite = TypeSpec.classBuilder("CancellationCheck")
                .addAnnotation(AnnotationSpec.builder(org.junit.jupiter.api.TestMethodOrder.class)
                        .addMember("value", "$T.class", org.junit.jupiter.api.MethodOrderer.MethodName.class).build())
                .addMethod(first).addMethod(second).build();
        var cancellation = org.junit.platform.engine.CancellationToken.create();
        var execution = new ScenarioRunSession.Execution(0, plan.target().project(),
                JavaFile.builder("proof", suite).build(), List.of(), cancellation);

        var executor = new InProcessScenarioExecutor(ScenarioDaemonLauncher.classpath());
        Map<String, ScenarioRunSession.Outcome> outcomes;
        try (var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var running = worker.submit(() -> executor.execute(execution));
            try {
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (Files.notExists(signal) && !running.isDone() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(signal));
                cancellation.cancel();
            } finally {
                Files.createFile(resume);
            }
            outcomes = running.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(ScenarioRunSession.Outcome.PASSED, outcomes.get("a()"), outcomes.toString());
        assertTrue(outcomes.get("b()") == null || outcomes.get("b()") == ScenarioRunSession.Outcome.SKIPPED,
                outcomes.toString());
    }

    @Test
    void reportsTheCurrentNativeStatementWithoutRecompilingAfterACommentLine() throws Exception {
        var source = Files.createDirectories(root.resolve("booking")).resolve("booking.vernac");
        var model = """
                namespace booking;
                id BookingId;
                value Seats(int);
                aggregate Booking[BookingId](Seats seats) behavior {
                    read int quote(boolean blocked) vernac {
                        if blocked {
                            require false else NoQuote.
                        }
                        return 5.
                    }
                };
                """;
        Files.writeString(source, model);
        var sources = new VersionedSources(root, Map.of(source, model));
        var planning = new ScenarioPlanningSession(root, Map.of(), List.of());
        var initial = planning.plan(Optional.empty(), new ScenarioPlanningSession.Inputs(sources.snapshot(), Map.of()), false, null);
        var previousOutput = root.resolve("previous-output");
        NativeBehaviorSourceMaps.write(initial.target().project(), previousOutput);
        var executor = new InProcessScenarioExecutor(ScenarioDaemonLauncher.classpath(), List.of(previousOutput));
        var selected = List.<AffectedScenarioSelector.Selection>of();
        var passed = Map.of("native source", ScenarioRunSession.Outcome.PASSED);
        assertEquals(passed, executor.execute(new ScenarioRunSession.Execution(0, initial.target().project(),
                sourceTraceProbe(7), selected)));

        String shifted = "// Added without changing the program\n" + model;
        Files.writeString(source, shifted);
        var after = sources.apply(0, List.of(new VersionedSources.Put(source, shifted)));
        var changed = planning.plan(Optional.empty(), new ScenarioPlanningSession.Inputs(after, Map.of()), false, null);
        assertEquals(passed, executor.execute(new ScenarioRunSession.Execution(1, changed.target().project(),
                sourceTraceProbe(8), selected)));
        assertEquals(1, executor.applicationCompilationCount());
    }

    private static JavaFile sourceTraceProbe(int expectedLine) {
        var booking = com.palantir.javapoet.ClassName.get("booking.domain", "Booking");
        var seats = com.palantir.javapoet.ClassName.get("booking.domain", "Seats");
        var probe = MethodSpec.methodBuilder("source")
                .addAnnotation(Test.class)
                .addAnnotation(AnnotationSpec.builder(DisplayName.class).addMember("value", "$S", "native source").build())
                .addStatement("var booking = $T.create($T.of(1))", booking, seats)
                .addStatement("var failure = $T.assertThrows($T.class, () -> booking.quote(true))",
                        Assertions.class, NativeValidation.Rejection.class)
                .addStatement("$T.assertEquals($S, failure.code())", Assertions.class, "NoQuote")
                .addStatement("$T.assertEquals(1, failure.getSuppressed().length)", Assertions.class)
                .addStatement("var trace = $T.assertInstanceOf($T.class, failure.getSuppressed()[0])",
                        Assertions.class, NativeBehaviorSources.SourceTrace.class)
                .addStatement("$T.assertEquals($S, trace.declaration())", Assertions.class, "booking.Booking.quote")
                .addStatement("$T.assertEquals($S, trace.source())", Assertions.class, "booking/booking.vernac")
                .addStatement("$T.assertEquals($L, trace.line())", Assertions.class, expectedLine)
                .addStatement("$T.assertEquals(21, trace.column())", Assertions.class)
                .build();
        return JavaFile.builder("booking", TypeSpec.classBuilder("SourceTraceCheck").addMethod(probe).build()).build();
    }

    @Test
    void executesOnlyTheSelectedStatementBranchAndRetainsStateOnRejection() throws Exception {
        var path = Path.of("booking/booking.vernac");
        var model = """
                namespace booking;
                id BookingId;
                value Seats(int count);
                aggregate Booking[BookingId](mut Seats seats) validates {
                    require self seats count <= 10 else CapacityExceeded.
                } behavior {
                    modify int reserve(Seats next, boolean approved) vernac {
                        let before = self seats count.
                        if approved {
                            let previous = before.
                            self replaceWith: next.
                            if previous == 0 {
                                return self seats count.
                            }
                        } else {
                            require before > 0 else ApprovalRequired.
                        }
                        let result = self seats count - before.
                        return result.
                    }
                    modify void replaceWith(Seats next) vernac {
                        self seats = next.
                    }
                    read int quote(boolean active) vernac {
                        if active {
                            let fee = 1.
                            return fee.
                        } else {
                            let fee = 2.
                            return fee.
                        }
                    }
                };
                """;
        var testSources = Map.of("booking.vernac", """
                namespace booking;
                suite ConditionalBooking {
                    scenario branchesAndRollsBack {
                        given booking = Booking(Seats(0));
                        given four = Seats(4);
                        given six = Seats(6);
                        given eleven = Seats(11);
                        when (booking reserve: four approved: false) rejects ApprovalRequired;
                        when added = (booking reserve: four approved: true);
                        expect (added == 4);
                        when more = (booking reserve: six approved: true);
                        expect (more == 2);
                        when ignored = (booking reserve: eleven approved: false);
                        expect (ignored == 0);
                        when (booking reserve: eleven approved: true) rejects CapacityExceeded;
                        expect (booking seats count == 6);
                        expect ((booking quote: true) == 1 && (booking quote: false) == 2);
                    }
                }
                """);
        var sources = new VersionedSources(root, Map.of(path, model));
        var planning = new ScenarioPlanningSession(root, Map.of(), List.of());
        var runner = new ScenarioRunSession(planning, new InProcessScenarioExecutor(ScenarioDaemonLauncher.classpath()), null);
        assertEquals(Map.of("booking.ConditionalBooking.branchesAndRollsBack", ScenarioRunSession.Outcome.PASSED),
                runner.run(new ScenarioPlanningSession.Inputs(sources.snapshot(), testSources), false).outcomes());

        var leaked = sources.apply(0, List.of(new VersionedSources.Put(path, model.replace("return result.", "return previous."))));
        var scopeError = assertThrows(SemanticValidationException.class,
                () -> runner.run(new ScenarioPlanningSession.Inputs(leaked, testSources), false));
        assertTrue(scopeError.getMessage().contains("Unknown native binding 'previous'"));

        var missingReturn = sources.apply(1, List.of(new VersionedSources.Put(path, model.replace("return result.", ""))));
        var returnError = assertThrows(SemanticValidationException.class,
                () -> runner.run(new ScenarioPlanningSession.Inputs(missingReturn, testSources), false));
        assertTrue(returnError.getMessage().contains("Native behavior must return a value"));
    }

    @Test
    void calculatesAnInvoiceWithExactDecimalsInNativeBehaviorValidationAndScenarios() throws Exception {
        var path = Path.of("billing/invoice.vernac");
        var model = """
                namespace billing;
                id InvoiceId;
                value UnitPrice(BigDecimal amount) list Prices, set UniquePrices;
                value TaxRate = REDUCED | STANDARD;
                value InvoiceLine(BigDecimal amount, boolean taxable) list InvoiceLines;
                value PurchaseLine(UnitPrice unitPrice) list PurchaseLines;
                aggregate Invoice[InvoiceId](mut UnitPrice price) validates {
                    require self price amount >= 0.00 && self price amount <= 100.00 else InvalidPrice.
                    require (self price amount == 0.0) ifTrue: true
                        ifFalse: ((self price amount dividedBy: 3.0 scale: 2 mode: RoundingMode HALF_UP) >= 0.0)
                        else InvalidInstallment.
                } behavior {
                    read BigDecimal totalFor(BigDecimal quantity, BigDecimal discount) vernac {
                        let net = self price amount * quantity - discount.
                        require net >= 0.0 && quantity > 0.0 else InvalidTotal.
                        return net + net * 0.19.
                    }
                    modify void reprice(UnitPrice nextPrice) vernac {
                        self price = nextPrice.
                    }
                    read BigDecimal roundedTotalFor(int quantity, TaxRate taxRate) vernac {
                        let net = self price amount * quantity asDecimal.
                        let rate = (taxRate == TaxRate REDUCED) ifTrue: 0.07 ifFalse: 0.19.
                        return (net + net * rate) roundedTo: 2 mode: RoundingMode HALF_UP.
                    }
                    read BigDecimal discountedThird(boolean eligible) vernac {
                        return eligible ifTrue: (1.0 dividedBy: 3.0 scale: 2 mode: RoundingMode HALF_UP)
                            ifFalse: (1.0 / 3.0).
                    }
                    read LocalDate dueOn(String issued, int days) vernac {
                        let issueDate = LocalDate fromIso: issued.
                        return issueDate plusDays: days.
                    }
                    read Duration netGrace(int days, int used) vernac {
                        return (Duration ofDays: days) minus: (Duration ofDays: used).
                    }
                    read BigDecimal taxableSubtotal(InvoiceLines lines, BigDecimal threshold) vernac {
                        let selected = lines select: [entry | entry taxable && entry amount >= threshold].
                        return selected sumBy: [entry | entry amount].
                    }
                    read Prices pricesOf(PurchaseLines lines) vernac {
                        return lines collect: [line | line unitPrice] into: Prices.
                    }
                };
                """;
        var sources = new VersionedSources(root, Map.of(path, model));
        var cases = """
                namespace billing;
                suite Pricing {
                    scenario calculatesDiscountAndTax {
                        given invoice = Invoice(UnitPrice(19.99));
                        when total = (invoice totalFor: 2.50 discount: (1.25 + 1.25));
                        expect (total == 56.49525 && total > 56.49 && total <= 56.50);
                        expect (0.1 + 0.2 == 0.30 && -1.25 + 1.25 == 0.0);
                        expect (1.0 / 8.0 == 0.125 && 5.5 % 2.0 == 1.50);
                        expect (true || 1.0 / 3.0 == 0.0);
                        expect ((invoice roundedTotalFor: 2 taxRate: TaxRate REDUCED) == 42.78);
                        expect ((invoice roundedTotalFor: 2 taxRate: TaxRate STANDARD) == 47.58);
                        expect ((invoice discountedThird: true) == 0.33);
                        expect (((3 asDecimal / 2.0) roundedTo: 0 mode: RoundingMode HALF_UP) == 2.0);
                        expect (true ifTrue: ((1.0 dividedBy: 3.0 scale: 2 mode: RoundingMode HALF_UP) == 0.33)
                            ifFalse: (1.0 / 3.0 == 0.0));
                    }
                    scenario rejectsInvalidPricingAndRetainsThePrice {
                        given invoice = Invoice(UnitPrice(0.0));
                        when (invoice totalFor: 1.0 discount: 1.0) rejects InvalidTotal;
                        given tooExpensive = UnitPrice(100.01);
                        when (invoice reprice: tooExpensive) rejects InvalidPrice;
                        expect (invoice price amount == 0.00);
                    }
                    scenario calculatesPaymentDatesAndGracePeriods {
                        given invoice = Invoice(UnitPrice(0.0));
                        when deadline = (invoice dueOn: "2024-02-28" days: 1);
                        expect (deadline == (LocalDate fromIso: "2024-02-29"));
                        expect ((deadline daysUntil: (deadline plusDays: 30)) == 30);
                        when grace = (invoice netGrace: 5 used: 2);
                        expect ((grace plus: (Duration ofDays: 1)) == (Duration ofDays: 4));
                    }
                    scenario filtersAndTotalsInvoiceLines {
                        given invoice = Invoice(UnitPrice(0.0));
                        given lines = InvoiceLines(InvoiceLine(10.0, true), InvoiceLine(25.50, true),
                            InvoiceLine(5.0, false), InvoiceLine(4.0, true));
                        given empty = InvoiceLines();
                        expect ((invoice taxableSubtotal: lines threshold: 5.0) == 35.50);
                        expect (((lines select: [entry | entry taxable]) sumBy: [entry | entry amount]) == 39.50);
                        expect ((empty sumBy: [entry | entry amount]) == 0.0);
                        expect ((lines sumBy: [entry | 1]) == 4);
                        expect (lines anySatisfy: [entry | (entry amount == 10.0) ifTrue: true
                            ifFalse: (1.0 / 3.0 == 0.0)]);
                    }
                    scenario projectsPricesIntoDeclaredCollections {
                        given invoice = Invoice(UnitPrice(0.0));
                        given first = UnitPrice(5.50);
                        given second = UnitPrice(3.00);
                        given lines = PurchaseLines(PurchaseLine(first), PurchaseLine(second), PurchaseLine(first));
                        given prices = Prices(first, second, first);
                        given unique = UniquePrices(first, second);
                        expect ((invoice pricesOf: lines) == prices);
                        expect ((lines collect: [line | line unitPrice] into: Prices) == prices);
                        expect ((lines collect: [line | line unitPrice] into: UniquePrices) == unique);
                    }
                }
                """;
        var planning = new ScenarioPlanningSession(root, Map.of(), List.of());
        var runner = new ScenarioRunSession(planning, new InProcessScenarioExecutor(ScenarioDaemonLauncher.classpath()), null);
        var testSources = Map.of("pricing.vernac", cases);
        assertEquals(Map.of("billing.Pricing.calculatesDiscountAndTax", ScenarioRunSession.Outcome.PASSED,
                "billing.Pricing.rejectsInvalidPricingAndRetainsThePrice", ScenarioRunSession.Outcome.PASSED,
                "billing.Pricing.calculatesPaymentDatesAndGracePeriods", ScenarioRunSession.Outcome.PASSED,
                "billing.Pricing.filtersAndTotalsInvoiceLines", ScenarioRunSession.Outcome.PASSED,
                "billing.Pricing.projectsPricesIntoDeclaredCollections", ScenarioRunSession.Outcome.PASSED),
                runner.run(new ScenarioPlanningSession.Inputs(sources.snapshot(), testSources), false).outcomes());

        var mixed = sources.apply(0, List.of(new VersionedSources.Put(path, model.replace("net * 0.19", "net * 19"))));
        var error = assertThrows(SemanticValidationException.class,
                () -> runner.run(new ScenarioPlanningSession.Inputs(mixed, testSources), false));
        assertTrue(error.getMessage().contains("Expected a non-optional BigDecimal"));

        var invalidMode = sources.apply(1, List.of(new VersionedSources.Put(path,
                model.replace("RoundingMode HALF_UP", "RoundingMode UNKNOWN"))));
        var modeError = assertThrows(SemanticValidationException.class,
                () -> runner.run(new ScenarioPlanningSession.Inputs(invalidMode, testSources), false));
        assertTrue(modeError.getMessage().contains("Expected a RoundingMode constant"));

        var incompatible = sources.apply(2, List.of(new VersionedSources.Put(path, model.replace("ifFalse: 0.19", "ifFalse: 19"))));
        var typeError = assertThrows(SemanticValidationException.class,
                () -> runner.run(new ScenarioPlanningSession.Inputs(incompatible, testSources), false));
        assertTrue(typeError.getMessage().contains("Conditional alternatives must have the same type"));

        var invalidBlock = sources.apply(3, List.of(new VersionedSources.Put(path,
                model.replace("entry taxable && entry amount >= threshold", "entry amount"))));
        var blockError = assertThrows(SemanticValidationException.class,
                () -> runner.run(new ScenarioPlanningSession.Inputs(invalidBlock, testSources), false));
        assertTrue(blockError.getMessage().contains("Expected a non-optional boolean"));

        var invalidProjection = sources.apply(4, List.of(new VersionedSources.Put(path,
                model.replace("[line | line unitPrice] into: Prices", "[line | line unitPrice amount] into: Prices"))));
        var projectionError = assertThrows(SemanticValidationException.class,
                () -> runner.run(new ScenarioPlanningSession.Inputs(invalidProjection, testSources), false));
        assertTrue(projectionError.getMessage().contains("Collection block must return a non-optional billing.UnitPrice"));
    }

    @Test
    void loadsCurrentTemplatesAndIgnoresRemovedProjectTemplates() throws Exception {
        var output = root.resolve("target/classes");
        String name = "templates/vernac-generated/app/Welcome.html";
        var old = output.resolve(name);
        Files.createDirectories(old.getParent());
        Files.writeString(old, "stale");
        Files.writeString(output.resolve("templates/handwritten.html"), "handwritten");
        var viewFile = Path.of("app/view.vernac");
        var sources = new VersionedSources(root.resolve("src/main/vernac"), Map.of(viewFile,
                "namespace app; view Welcome() { page \"Fresh view\" { heading \"Fresh template\"; } }"));
        var planning = new ScenarioPlanningSession(root.resolve("src/main/vernac"), Map.of(), List.of());
        var executor = new InProcessScenarioExecutor(ScenarioDaemonLauncher.classpath(), List.of(output));
        var probe = MethodSpec.methodBuilder("resources")
                .addAnnotation(Test.class)
                .addAnnotation(AnnotationSpec.builder(DisplayName.class).addMember("value", "$S", "resources").build())
                .addException(Exception.class)
                .addStatement("var loader = $T.currentThread().getContextClassLoader()", Thread.class)
                .addStatement("var template = loader.getResource($S)", name)
                .addStatement("var matches = $T.list(loader.getResources($S))", java.util.Collections.class, name)
                .addStatement("$T.assertEquals(template == null ? 0 : 1, matches.size())", Assertions.class)
                .beginControlFlow("if (template != null)")
                .beginControlFlow("try (var input = template.openStream())")
                .addStatement("$T.assertTrue(new String(input.readAllBytes(), $T.UTF_8).contains($S))",
                        Assertions.class, StandardCharsets.class, "Fresh template")
                .endControlFlow()
                .endControlFlow()
                .addStatement("$T.assertNotNull(loader.getResource($S))", Assertions.class, "templates/handwritten.html");
        var currentProbe = probe.build().toBuilder().addStatement("$T.assertNotNull(template)", Assertions.class).build();
        var bridge = JavaFile.builder("app", TypeSpec.classBuilder("ResourceChecks").addMethod(currentProbe).build()).build();
        var original = planning.plan(Optional.empty(), new ScenarioPlanningSession.Inputs(sources.snapshot(), Map.of()), false, null);
        assertEquals(1, original.target().project().webTemplates().size());
        assertEquals(Map.of("resources", ScenarioRunSession.Outcome.PASSED), executor.execute(new ScenarioRunSession.Execution(
                0, original.target().project(), bridge, List.of())));

        var removed = sources.apply(0, List.of(new VersionedSources.Delete(viewFile)));
        var next = planning.plan(Optional.empty(), new ScenarioPlanningSession.Inputs(removed, Map.of()), false, null);
        assertEquals(Map.of(), next.target().project().webTemplates());
        var absentProbe = probe.addStatement("$T.assertNull(template)", Assertions.class).build();
        var absentBridge = JavaFile.builder("app", TypeSpec.classBuilder("ResourceChecks").addMethod(absentProbe).build()).build();
        assertEquals(Map.of("resources", ScenarioRunSession.Outcome.PASSED), executor.execute(new ScenarioRunSession.Execution(
                1, next.target().project(), absentBridge, List.of())));
    }

    @Test
    void executesWithJupiterAndLoadsFreshClassesAfterAnEdit() throws Exception {
        var classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
        var executor = new InProcessScenarioExecutor(classpath);
        var runner = new ScenarioRunSession(new ScenarioPlanningSession(root, Map.of(), List.of()), executor, null);
        var path = Path.of("app/counter.vernac");
        var sources = new VersionedSources(root, Map.of(path, "namespace app; value Counter(int);"));
        var tests = Map.of("checks.vernac", """
                namespace app;
                suite Checks {
                    scenario counter { given counter = Counter(1); expect counter.intValue equals 1; }
                }
                """);
        var initial = new ScenarioPlanningSession.Inputs(sources.snapshot(), tests);
        var originalLoader = Thread.currentThread().getContextClassLoader();
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.PASSED), runner.run(initial, false).outcomes());

        assertEquals(2, executor.compilationCount());
        assertEquals(1, executor.applicationCompilationCount());

        var editedTests = Map.of("checks.vernac", tests.get("checks.vernac").replace("equals 1", "equals 2"));
        var testOnly = runner.run(new ScenarioPlanningSession.Inputs(initial.main(), editedTests), false);
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.FAILED), testOnly.outcomes());
        assertEquals(3, executor.compilationCount());
        assertEquals(1, executor.applicationCompilationCount());

        var rejected = sources.apply(0, List.of(new VersionedSources.Put(path,
                "namespace app; value Counter(int) validates { require self intValue > 1 else TooSmall. };")));
        var result = runner.run(new ScenarioPlanningSession.Inputs(rejected, tests), false);
        assertEquals(1, result.revision());
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.FAILED), result.outcomes());
        assertEquals(5, executor.compilationCount());
        assertEquals(2, executor.applicationCompilationCount());

        var changed = new ScenarioPlanningSession.Inputs(rejected, tests);
        assertEquals(result.outcomes(), runner.run(changed, false).outcomes());
        assertEquals(5, executor.compilationCount());
        assertEquals(result.outcomes(), runner.run(changed, true).outcomes());
        assertEquals(7, executor.compilationCount());
        assertEquals(3, executor.applicationCompilationCount());
        assertSame(originalLoader, Thread.currentThread().getContextClassLoader());
    }
}
