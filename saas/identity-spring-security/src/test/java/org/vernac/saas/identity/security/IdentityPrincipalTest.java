// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.domain.UserId;

class IdentityPrincipalTest {
    private byte[] write(Object principal) throws Exception {
        var bytes=new ByteArrayOutputStream();try(var output=new ObjectOutputStream(bytes)){output.writeObject(principal);}return bytes.toByteArray();
    }
    private Object read(byte[] bytes) throws Exception {try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes))){return input.readObject();}}
    @Test void technicalSessionRoundTripPreservesNominalIdentityWithoutSerializingDomainObjects() throws Exception {
        var id=UserId.create();var principal=new IdentityPrincipal(id);
        var restored=(IdentityPrincipal)read(write(principal));
        assertEquals(principal,restored);assertEquals(principal.hashCode(),restored.hashCode());assertEquals(id,restored.userId());
        assertEquals(id.asString(),restored.getName());assertFalse(restored.toString().contains(id.asString()));
        assertFalse(Serializable.class.isAssignableFrom(UserId.class));assertNotEquals(principal,new IdentityPrincipal(UserId.create()));
    }
    @Test void rejectsMissingSubjectsAtConstructionAndDeserialization() throws Exception {
        assertThrows(NullPointerException.class,()->new IdentityPrincipal(null));
        var malformed=new IdentityPrincipal(UserId.create());var subject=IdentityPrincipal.class.getDeclaredField("subject");subject.setAccessible(true);subject.set(malformed,null);
        var bytes=write(malformed);var error=assertThrows(InvalidObjectException.class,()->read(bytes));assertEquals("Invalid identity principal",error.getMessage());
    }
}
