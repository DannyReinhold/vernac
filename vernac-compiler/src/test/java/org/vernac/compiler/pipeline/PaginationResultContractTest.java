// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.TypeNode;
import org.vernac.compiler.symbols.ResolvedType;
import static org.junit.jupiter.api.Assertions.*;

class PaginationResultContractTest {
    private static final String BASE="namespace example; id IssueId; value Title(String) list Titles, set SelectedTitles; aggregate Issue[IssueId](Title) list Issues; ";
    private void reject(String source,String message) {
        var failure=assertThrows(SemanticValidationException.class,()->new VernacCompiler().compileSource(BASE+source));
        assertTrue(failure.getMessage().contains(message),failure.getMessage());
    }
    @Test void resultTypesAreExplicitAndUseValueListsRatherThanAggregateGraphs() {
        var result=new VernacCompiler().compileSource(BASE+"""
            usecase Board() returns (Title, counted offset Titles open, offset Titles closed, cursor Titles activity)
                behavior { execute { throw new UnsupportedOperationException(); } }
            usecase Single() returns cursor Titles behavior { execute { throw new UnsupportedOperationException(); } }
            """);
        String java=result.generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertTrue(java.contains("CountedOffsetPage<Title, Titles> open"));
        assertTrue(java.contains("OffsetPage<Title, Titles> closed"));
        assertTrue(java.contains("CursorPage<Title, Titles> activity"));
        assertFalse(java.contains("Object open"));
        reject("usecase Bad() returns offset SelectedTitles behavior {execute {}}","declared list");
        reject("usecase Bad() returns offset Issues behavior {execute {}}","project aggregates first");
        reject("usecase Bad() returns cursor Title behavior {execute {}}","declared list");
        reject("usecase Bad() returns cursor Titles? behavior {execute {}}","must be required");
        reject("usecase Bad() returns (offset Titles, cursor Titles) behavior {execute {}}","titles");
        reject("usecase Bad() returns (mut offset Titles page) behavior {execute {}}","cannot declare mut");
    }
    @Test void dtoMappingsPreserveTheVariantAndCannotDiscardMetadataImplicitly() {
        String usecase="usecase Board() returns (counted offset Titles page) behavior {execute {throw new UnsupportedOperationException();}} ";
        String adapter="adapter Api inbound http uses Board board {base \"/\"; get {GET \"/\"; execute board {} respond 200 Reply using output;} ";
        reject(usecase+adapter+"schema Reply(offset list<String> page); mapping output: Reply <- Board.Result {page <- source.page;} }","No exact conversion");
        reject(usecase+adapter+"schema Reply(list<String> page); mapping output: Reply <- Board.Result {page <- source.page;} }","No exact conversion");
        reject(usecase+adapter+"schema Reply(String page); mapping output: Reply <- Board.Result {page <- source.page.items;} }","Map a page as a whole");
        reject(usecase+adapter+"schema Reply(counted offset String page); mapping output: Reply <- Board.Result {page <- source.page;} }","DTO page types require");
        reject(usecase+adapter+"schema Reply(patch counted offset list<String> page); mapping output: Reply <- Board.Result {page <- source.page;} }","PATCH page fields");
        reject(usecase+adapter+"schema Reply(cursor list<Reply> page); mapping output: Reply <- Board.Result {page <- source.page;} }","Cyclic DTO containment");
    }
}
