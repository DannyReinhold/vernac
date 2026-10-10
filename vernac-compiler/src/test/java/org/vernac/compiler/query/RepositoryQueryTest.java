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
    @Test void validatesListAndSetOrdering() throws Exception {
        source(MODEL,"find Tours all();");
        assertTrue(new VernacCompiler().analyzeProject(root).diagnostics().stream().anyMatch(d->d.message().contains("order is unspecified")));
        source(MODEL.replace("list Tours","set Tours"),"find Tours all() order by title;");
        assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root));
    }
}
