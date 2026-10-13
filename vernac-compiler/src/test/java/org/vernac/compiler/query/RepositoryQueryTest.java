// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.query;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryQueryTest {
    @TempDir Path root;
    private static final String MODEL="""
        namespace queries;
        id TourId;
        value Title(String);
        value OtherTitle(String);
        value Price(BigDecimal);
        value Ratio(double);
        aggregate Tour[TourId](Title title, Title? reference, Price price, Ratio ratio) list Tours;
        """;
    private void source(String model,String method) throws Exception {
        Files.createDirectories(root.resolve("queries"));
        Files.writeString(root.resolve("queries/model.vernac"),model+"\nrepository ToursRepository for Tour { "+method+" }");
    }
    private void rejects(String method,String message) throws Exception {
        source(MODEL,method);
        var failure=assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root));
        assertTrue(failure.getMessage().contains(message),failure.getMessage());
    }
    @Test void resolvesAllOperatorsAndGeneratesOptionalAndCollectionResults() throws Exception {
        source(MODEL,"""
            find Tours range(Price low, Price high, Title excluded)
                where price >= :low and price < :high and title != :excluded order by price desc, createdAt;
            find Tour? unique(Title title) where title = :title;
            find Tours absent() where reference is absent order by updatedAt;
            find Tours present() where reference is present order by title;
            find Tours before(Instant time) where createdAt <= :time order by createdAt;
            find Tours after(Instant time) where updatedAt > :time order by updatedAt;
            """);
        var result=new VernacCompiler().compileProject(root);
        String java=result.generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertTrue(java.contains("Optional<Tour> unique"));
        assertTrue(java.contains("Tours range"));
        assertTrue(java.contains("ScalarQuery"));
        assertFalse(java.contains("UnsupportedOperationException"));
        assertEquals(6,new VernacCompiler().analyzeProject(root).queries().size());
    }
    @Test void diagnosesNamesTypesCardinalityAndCapabilities() throws Exception {
        rejects("find Missing all();","explicitly declared");
        rejects("find Tour all();","explicitly declared");
        rejects("find Tours all(OtherTitle title) where title = :title;","same Vernac type");
        rejects("find Tours all() where missing is absent;","Unknown aggregate query field");
        rejects("find Tours all() where title = :missing;","Unknown query parameter");
        rejects("find Tours all() where title is absent;","optional field");
        rejects("find Tours all(Ratio r) where ratio > :r;","Range comparison");
        rejects("find Tours all() order by ratio;","Ordering is not supported");
        rejects("find Tour? all() order by title;","singleton queries cannot");
        rejects("find Tours save();","reserved repository method");
        rejects("find Tours all(Title? title) where title = :title;","non-optional");
    }
    @Test void textSearchUsesStringParametersWithoutUnwrappingThem() throws Exception {
        source(MODEL, """
            find Tours matching(String pattern) where title like :pattern order by title;
            find Tours containing(String text) where reference contains :text order by title;
            find Tours beginning(String text) where title starts with :text order by title;
            find Tours ending(String text) where title ends with :text order by title;
            """);
        var result = new VernacCompiler().compileProject(root);
        String java = result.generatedFiles().stream().map(Object::toString).reduce("", String::concat);
        assertFalse(java.contains("pattern.string()"));
        assertFalse(java.contains("text.string()"));
        assertEquals(4, new VernacCompiler().analyzeProject(root).queries().size());
        rejects("find Tours matching(Title pattern) where title like :pattern;", "String parameter");
        rejects("find Tours matching(String pattern) where price contains :pattern;", "String-backed");
        rejects("find Tours matching(String text) where title = :text;", "same Vernac type");
    }
    @Test void booleanTreePreservesPrecedenceAndChecksEveryBranch() throws Exception {
        source(MODEL,"find Tours matching(Title a, Title b) where title = :a or not (reference = :b and not title = :a) order by title;");
        var query = new VernacCompiler().analyzeProject(root).queries().values().iterator().next();
        var tree = (org.vernac.compiler.ast.RepositoryMethodNode.Junction) query.method().condition();
        assertEquals("or", tree.operator());
        assertEquals(2, tree.children().size());
        assertEquals(3, query.predicates().size());
        rejects("find Tours bad(Title a) where title = :a or not (missing = :a);", "Unknown aggregate query field");
        rejects("find Tours bad(Title a, OtherTitle b) where title = :a or title = :b;", "same Vernac type");
    }
    @Test void validatesListAndSetOrdering() throws Exception {
        source(MODEL,"find Tours all();");
        assertTrue(new VernacCompiler().analyzeProject(root).diagnostics().stream().anyMatch(d->d.message().contains("order is unspecified")));
        source(MODEL.replace("list Tours","set Tours"),"find Tours all() order by title;");
        assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root));
    }
    @Test void queryResultsSelectAnyDeclaredCollectionAndRespectItsKind() throws Exception {
        String model = MODEL.replace("list Tours;", "set SelectedTours, list Tours, list RecentTours;");
        source(model, "find RecentTours recent() where reference is absent order by createdAt; find SelectedTours selected() where reference is present;");
        var result = new VernacCompiler().compileProject(root);
        String jdbc = result.generatedFiles().stream().filter(f -> f.typeSpec().name().startsWith("Jdbc"))
                .map(Object::toString).reduce("", String::concat);
        assertTrue(jdbc.contains("RecentTours"));
        assertTrue(jdbc.contains("SelectedTours"));
        source(model, "find SelectedTours selected() where reference is present order by createdAt;");
        var failure = assertThrows(SemanticValidationException.class, () -> new VernacCompiler().analyzeProject(root));
        assertTrue(failure.getMessage().contains("set result cannot preserve order by"), failure.getMessage());
    }

    @Test void resultCannotBorrowACollectionFromAnotherAggregateWithTheSameIdType() throws Exception {
        source(MODEL + "aggregate Other[TourId]() list Others;", "find Others all() where reference is absent;");
        var failure = assertThrows(SemanticValidationException.class, () -> new VernacCompiler().analyzeProject(root));
        assertTrue(failure.getMessage().contains("explicitly declared list or set of Tour"), failure.getMessage());
    }

    @Test void offsetQueriesRequireListsAndAnExplicitIdentityTieBreaker() throws Exception {
        source(MODEL, "find offset Tours paged(Title title) where title = :title order by createdAt desc, id asc; find offset Tours counted() order by id desc counting;");
        String java=new VernacCompiler().compileProject(root).generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertTrue(java.contains("OffsetQuery<Tour, Tours> paged"));
        assertTrue(java.contains("CountedOffsetQuery<Tour, Tours> counted"));
        assertTrue(java.contains("CountedOffsetPage.of"));
        assertTrue(java.contains("store.offset"));
        rejects("find offset Tours paged();", "final explicit tie-breaker");
        rejects("find offset Tours paged() order by title;", "final explicit tie-breaker");
        rejects("find offset Tour? paged() order by id;", "declared list");
        rejects("find Tours counted() order by title counting;", "counting requires");
        source(MODEL.replace("list Tours", "set Tours"), "find offset Tours paged() order by id;");
        var failure=assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root));
        assertTrue(failure.getMessage().contains("declared list"),failure.getMessage());
    }

    @Test void paginationKeywordsRemainUsableAsNamespaceAndMemberSegments() throws Exception {
        Path folder=Files.createDirectories(root.resolve("queries/offset/counting"));
        Files.writeString(folder.resolve("model.vernac"),MODEL.replace("namespace queries;","namespace queries.offset.counting;")
                + "repository TourRepository for Tour { find offset Tours offset(Title counting) where title = :counting order by id counting; }");
        assertEquals(1,new VernacCompiler().analyzeProject(root).queries().size());
    }

    @Test void cursorQueriesRequireStableTotalOrderingAndRequiredSortFields() throws Exception {
        source(MODEL,"find cursor Tours following(Title title) where title = :title order by price desc, createdAt asc, id desc;");
        var project=new VernacCompiler().analyzeProject(root);
        assertTrue(project.queries().values().iterator().next().method().cursor());
        String java=new VernacCompiler().compileProject(root).generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertTrue(java.contains("CursorQuery<Tour, Tours> following"));
        assertTrue(java.contains("JdbcCursorSort"));
        assertTrue(java.contains("CursorPage.of"));
        rejects("find cursor Tours following() order by title;","final explicit tie-breaker");
        rejects("find cursor Tours following() order by reference, id;","must be required");
        rejects("find cursor Tours following() order by ratio, id;","Ordering is not supported");
        rejects("find cursor Tours following() order by id counting;","counting requires an offset");
        rejects("find cursor Tour? following() order by id;","declared list");
    }

}
