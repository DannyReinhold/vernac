// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;
class InboundErrorPolicyContractTest {
    private static final String MODEL="namespace demo; value Reason = CLOSED; usecase Run() behavior {execute {}} ";
    private String api(String endpoint,String adapter) {
        return MODEL+"adapter Api inbound http uses Run run {base \"/api\"; call {POST \"/\"; execute run {} respond 204; "+endpoint+"} "+adapter+"}";
    }
    @Test void compilesOrderedEndpointAndAdapterRulesAndTypedEnums() {
        var result=new VernacCompiler().compileSource(api("errors {when rejection Reason.CLOSED then respond 409; otherwise default;}",
            "errors {when integration remote TIMEOUT then respond 503 \"DEPENDENCY_UNAVAILABLE\" \"Try later.\"; when validation then respond 400; otherwise default;}"));
        var generated=result.generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertTrue(generated.contains("Reason.CLOSED"));assertTrue(generated.contains("IntegrationCategory.TIMEOUT"));
        assertTrue(generated.indexOf("Reason.CLOSED")<generated.indexOf("IntegrationCategory.TIMEOUT"));
        assertTrue(generated.contains("withStatus(error, 409)"));
        new VernacCompiler().compileSource(api("errors implemented by demo.EndpointErrors;","errors implemented by demo.AdapterErrors;"));
    }
    @Test void rejectsUnknownCodesCategoriesDuplicateSelectorsAndInvalidReplies() {
        for(String rule:new String[]{
            "when rejection Reason.UNKNOWN then respond 409;",
            "when rejection Run.CLOSED then respond 409;",
            "when rejection CLOSED then respond 409;",
            "when integration remote TYPO then respond 503;",
            "when validation then respond 200;",
            "when validation then respond 600;",
            "when validation then respond 400 \"\" \"Message\";",
            "when validation then respond 400 \"CODE\" \" \";",
            "when validation then respond 400; when validation then respond 422;"
        }) assertThrows(SemanticValidationException.class,()->new VernacCompiler().compileSource(api("","errors {"+rule+"}")),rule);
        assertThrows(SemanticValidationException.class,()->new VernacCompiler().compileSource(api("errors {when validation then respond 400 \"CODE\";}","")));
    }
}
