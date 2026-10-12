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
}
