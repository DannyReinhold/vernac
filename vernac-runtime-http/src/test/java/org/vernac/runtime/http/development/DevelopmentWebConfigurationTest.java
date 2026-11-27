// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.development;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.vernac.runtime.DevelopmentRevisions;
import org.vernac.runtime.NativeBehavior;
import org.vernac.runtime.NativeBehaviorExpression;
import org.vernac.runtime.NativeBehaviorLayout;
import org.vernac.runtime.NativeBehaviorSources;
import org.vernac.runtime.NativeValidation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevelopmentWebConfigurationTest {
    private static final String VIEW = "vernac-generated/example/Board";
    private static final Class<?> IMPLEMENTATION = DevelopmentWebConfigurationTest.class;
    private static final DevelopmentRevisions.BehaviorKey KEY = new DevelopmentRevisions.BehaviorKey(IMPLEMENTATION, 0);
    private static final NativeBehavior.Program<Object> PROGRAM = new NativeBehavior.Program<>(
            1, 0, List.of(new NativeBehavior.Return<>(ignored -> 1)), IMPLEMENTATION, 0);

    @Test
    void rendersTheBehaviorAndTemplateFromTheRevisionPinnedBeforeActivation() throws Exception {
        var config = new DevelopmentWebConfiguration();
        var revisions = config.vernacDevelopmentRevisions();
        var filter = config.vernacDevelopmentRevisionFilter(revisions);
        var engine = new TemplateEngine();
        engine.setTemplateResolver(config.vernacDevelopmentTemplateResolver(revisions));

        filter.doFilter(null, null, (request, response) -> assertEquals("<p>Packaged 1</p>", render(engine)));
        revisions.activate(0, Map.of(KEY, update(2)), Map.of(VIEW + ".html", "<p>First [[${value}]]</p>"));
        var requestStarted = new CompletableFuture<Integer>();
        var resumeRequest = new CompletableFuture<Void>();
        var oldRequest = CompletableFuture.runAsync(() -> {
            try {
                filter.doFilter(null, null, (request, response) -> {
                    requestStarted.complete(execute());
                    resumeRequest.join();
                    assertEquals("<p>First 2</p>", render(engine));
                });
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        try {
            assertEquals(2, requestStarted.get(5, TimeUnit.SECONDS));
            revisions.activate(1, Map.of(KEY, update(3)), Map.of(VIEW + ".html", "<p>Second [[${value}]]</p>"));
            filter.doFilter(null, null, (request, response) -> assertEquals("<p>Second 3</p>", render(engine)));
        } finally {
            resumeRequest.complete(null);
        }
        oldRequest.get(5, TimeUnit.SECONDS);
        assertEquals("<p>Packaged 1</p>", render(engine));
    }

    @Test
    void rejectsAnIncompatibleLayoutBeforePublishingEitherChange() throws Exception {
        var config = new DevelopmentWebConfiguration();
        var revisions = config.vernacDevelopmentRevisions();
        var filter = config.vernacDevelopmentRevisionFilter(revisions);
        var engine = new TemplateEngine();
        engine.setTemplateResolver(config.vernacDevelopmentTemplateResolver(revisions));
        revisions.activate(0, Map.of(KEY, update(2)), Map.of(VIEW + ".html", "<p>First [[${value}]]</p>"));
        var invalid = new DevelopmentRevisions.BehaviorUpdate(new byte[]{1}, new byte[0]);
        assertThrows(IOException.class,
                () -> revisions.activate(1, Map.of(KEY, invalid), Map.of(VIEW + ".html", "<p>Invalid</p>")));
        assertThrows(IllegalStateException.class, () -> revisions.activate(0, Map.of(), Map.of()));
        assertEquals(1, revisions.revision());
        filter.doFilter(null, null, (request, response) -> assertEquals("<p>First 2</p>", render(engine)));
    }

    @Test
    void reportsSourceLocationsFromThePinnedBehaviorRevision() throws Exception {
        var revisions = new DevelopmentRevisions();
        var program = new NativeBehavior.Program<Object>(1, 0,
                List.of(new NativeBehavior.Require<>(ignored -> true, "Denied")), IMPLEMENTATION, 1);
        var key = new DevelopmentRevisions.BehaviorKey(IMPLEMENTATION, 1);
        var layout = new ByteArrayOutputStream();
        NativeBehaviorLayout.write(layout, List.of(new NativeBehaviorLayout.Instruction(
                NativeBehaviorLayout.Kind.REQUIRE, new NativeBehaviorExpression.Literal(false))));
        var source = new ByteArrayOutputStream();
        NativeBehaviorSources.write(source, "example.Board.complete", "example/board.vernac",
                List.of(new NativeBehaviorSources.Position("0", 17, 5)));
        revisions.activate(0, Map.of(key, new DevelopmentRevisions.BehaviorUpdate(layout.toByteArray(),
                source.toByteArray())), Map.of());
        try (var pinned = revisions.pin()) {
            var failure = assertThrows(NativeValidation.Rejection.class,
                    () -> program.execute(new Object(), List.of(), IMPLEMENTATION, 1));
            var trace = (NativeBehaviorSources.SourceTrace) failure.getSuppressed()[0];
            assertEquals("example/board.vernac", trace.source());
            assertEquals(17, trace.line());
        }
    }

    private static DevelopmentRevisions.BehaviorUpdate update(int value) throws IOException {
        var layout = new ByteArrayOutputStream();
        NativeBehaviorLayout.write(layout, List.of(new NativeBehaviorLayout.Instruction(
                NativeBehaviorLayout.Kind.RETURN, new NativeBehaviorExpression.Literal(value))));
        return new DevelopmentRevisions.BehaviorUpdate(layout.toByteArray(), new byte[0]);
    }

    private static int execute() {
        return (int) PROGRAM.execute(new Object(), List.of(), IMPLEMENTATION, 0);
    }

    private static String render(TemplateEngine engine) {
        var context = new Context();
        context.setVariable("value", execute());
        return engine.process(VIEW, context);
    }
}
