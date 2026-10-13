package org.vernac.runtime.http;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.*;
import org.vernac.runtime.http.inbound.*;
import org.vernac.runtime.json.MappingContractException;
import static org.junit.jupiter.api.Assertions.*;

class InboundHttpRuntimeTest {
    @Test void wireValuesAreExplicitAndDoNotCaptureRejectedInputs() {
        UUID id=UUID.randomUUID();assertEquals(id,WireValues.parse("UUID",id.toString()));
        assertEquals(true,WireValues.parse("boolean","true"));assertEquals(42,WireValues.parse("Integer","42"));
        assertThrows(WireValueException.class,()->WireValues.parse("UUID","1-1-1-1-1"));
        var error=assertThrows(WireValueException.class,()->WireValues.parse("Boolean","private-invalid-value"));
        assertFalse(error.getMessage().contains("private-invalid-value"));assertNull(error.getCause());
    }
    @Test void responseMappingFailuresStayTechnicalAndNestedInputValidationStaysDomain() {
        var domain=new DomainValidationException("private");
        var nested=new MappingContractException("nested","$",domain);
        assertEquals(422,InboundHttpErrors.defaultReply(new MappingContractException("outer","$",nested)).status());
        assertEquals(500,InboundHttpErrors.defaultReply(InboundHttpErrors.response("create",domain)).status());
        assertEquals(500,InboundHttpErrors.defaultReply(new IllegalStateException("private")).status());
        assertEquals(404,InboundHttpErrors.defaultReply(new AggregateNotFoundException(Object.class,"id")).status());
    }
    private enum Rejection { CLOSED }
    private enum OtherRejection { CLOSED }
    @Test void policiesMatchTypedCodesAndExactResponsibilityWithoutUnwrappingTechnicalFailures() {
        var invocation=org.vernac.runtime.ports.AdapterInvocation.create("Port","Adapter","operation");
        var rejected=new org.vernac.runtime.ports.PortRejectedException(invocation,Rejection.CLOSED,"private payload");
        assertTrue(InboundHttpErrors.rejection(rejected,Rejection.CLOSED));
        assertFalse(InboundHttpErrors.rejection(rejected,OtherRejection.CLOSED));
        var remote=new org.vernac.runtime.ports.RemoteIntegrationException(invocation,org.vernac.runtime.ports.IntegrationCategory.TIMEOUT,"private upstream");
        assertTrue(InboundHttpErrors.integration(remote,"remote",org.vernac.runtime.ports.IntegrationCategory.TIMEOUT));
        assertFalse(InboundHttpErrors.integration(remote,"unknown",org.vernac.runtime.ports.IntegrationCategory.TIMEOUT));
        assertSame(remote,InboundHttpErrors.policyError(InboundHttpErrors.invocation("run",remote)));
        var response=InboundHttpErrors.response("run",rejected);
        assertFalse(InboundHttpErrors.rejection(response,Rejection.CLOSED));
        assertFalse(InboundHttpErrors.matches(response,"domainrejection"));
        assertEquals("CLOSED",InboundHttpErrors.withStatus(rejected,409).code());
        assertFalse(InboundHttpErrors.withStatus(rejected,409).message().contains("private"));
        var cursor=new org.vernac.runtime.pagination.InvalidCursorException();
        assertTrue(InboundHttpErrors.matches(cursor,"invalidcursor"));
        assertFalse(InboundHttpErrors.matches(cursor,"validation"));
    }
}
