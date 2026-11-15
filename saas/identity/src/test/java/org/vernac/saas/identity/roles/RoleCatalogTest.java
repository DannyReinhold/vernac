// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.roles;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.authorization.PermissionKey;
class RoleCatalogTest {
    enum Rights implements PermissionKey { READ,EDIT;public String qualifiedName(){return "example.Documents."+name();} }
    static final String VALID="""
        {"format":1,"roles":[{"key":"example.reader","permissions":["example.Documents.READ"]},
        {"key":"example.editor","permissions":["example.Documents.READ","example.Documents.EDIT"]}]}
        """;
    static RoleCatalog read(String json) { return RoleCatalog.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),List.of(Rights.values())); }
    @Test void exactUnionIsImmutableAndCatalogOrderDoesNotChangeItsFingerprint() {
        var catalog=read(VALID);assertEquals(Set.of(PermissionKey.of(Rights.READ.qualifiedName()),PermissionKey.of(Rights.EDIT.qualifiedName())),catalog.permissions(List.of("example.reader","example.editor")));
        assertTrue(catalog.permissions(List.of()).isEmpty());assertThrows(UnsupportedOperationException.class,()->catalog.roleKeys().clear());
        assertThrows(UnsupportedOperationException.class,()->catalog.permissions(List.of("example.reader")).clear());
        assertEquals(catalog.fingerprint(),read(VALID.replace("[\"example.Documents.READ\",\"example.Documents.EDIT\"]","[\"example.Documents.EDIT\",\"example.Documents.READ\"]")).fingerprint());
    }
    @Test void malformedDuplicateUnknownAndOverLimitCatalogsFailWithoutEchoingInput() {
        for(String json:List.of("null","[]",VALID+" {}",VALID.replace("\"format\":1","\"format\":2"),VALID.replace("\"format\":1","\"format\":1,\"format\":1"),
            VALID.replace("\"format\":1","\"extra\":true,\"format\":1"),VALID.replace("example.editor","example.reader"),
            VALID.replace("example.Documents.EDIT","unknown.secret"),VALID.replace("example.Documents.EDIT","example.Documents.READ"),
            VALID.replace("example.reader","*"),VALID.replace("\"key\":\"example.reader\"","\"key\":\"example.reader\",\"label\":\"secret\"")," ".repeat(RoleCatalog.MAX_BYTES+1))) {
            var error=assertThrows(IllegalArgumentException.class,()->read(json));assertNull(error.getCause());assertEquals("Invalid Identity role catalog",error.getMessage());
        }
        assertThrows(IllegalArgumentException.class,()->RoleCatalog.read(new ByteArrayInputStream(VALID.getBytes(StandardCharsets.UTF_8)),List.of(PermissionKey.of("example.Documents.READ"))));
        assertThrows(IllegalArgumentException.class,()->RoleCatalog.read(new ByteArrayInputStream(VALID.getBytes(StandardCharsets.UTF_8)),List.of(Rights.READ,Rights.READ)));
        var roles=new java.util.ArrayList<String>();for(int i=0;i<129;i++)roles.add("{\"key\":\"example.role"+i+"\",\"permissions\":[]}");
        assertThrows(IllegalArgumentException.class,()->read("{\"format\":1,\"roles\":["+String.join(",",roles)+"]}"));
        assertThrows(IllegalArgumentException.class,()->read(VALID.replace("[\"example.Documents.READ\"]","["+String.join(",",Collections.nCopies(129,"\"example.Documents.READ\""))+"]")));

    }
    @Test void missingRemovedDuplicateAndUnboundedAssignmentsNeverGrant() {
        var catalog=read(VALID);for(var selected:List.of(List.of("example.owner"),List.of("example.reader","example.reader"),Collections.nCopies(33,"example.reader")))
            assertThrows(IllegalArgumentException.class,()->catalog.permissions(selected));
        assertThrows(IllegalArgumentException.class,()->RoleCatalog.fromClasspath(getClass().getClassLoader(),"https://invalid/catalog.json",List.of(Rights.values())));
        assertThrows(IllegalArgumentException.class,()->RoleCatalog.fromClasspath(getClass().getClassLoader(),"missing.json",List.of(Rights.values())));
    }
}
