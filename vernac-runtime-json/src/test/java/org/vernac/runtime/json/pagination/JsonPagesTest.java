// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json.pagination;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainList;
import org.vernac.runtime.pagination.*;
import org.vernac.runtime.json.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonPagesTest {
    private record Values<T>(List<T> asList) implements DomainList<T> { public Iterator<T> iterator(){return asList.iterator();} }
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void countedMetadataAndEmptyWindowsRoundTripExactly() throws Exception {
        var source=CountedOffsetPage.of(new Values<>(List.of("a","b")),OffsetRequest.ofOffset(1,2),5);
        var mapped=JsonPages.mapCountedOffsetPage(source,(value,index)->value.toUpperCase(Locale.ROOT));
        assertEquals(List.of("A","B"),mapped.items()); assertTrue(mapped.pageNumber().isEmpty());
        var wire=JsonPages.writeCountedOffsetPage(mapped,JsonValues::scalarNode);
        assertFalse(wire.has("pageNumber")); assertFalse(wire.has("request"));
        assertEquals(mapped,JsonPages.readCountedOffsetPage(wire,"$",(node,path)->JsonValues.scalar(node,path,String.class)));
        var empty=JsonPages.mapCountedOffsetPage(CountedOffsetPage.of(new Values<String>(List.of()),OffsetRequest.ofPage(9,2),5),(value,index)->value);
        assertEquals(5,empty.totalElements());assertEquals(Optional.of(9L),empty.pageNumber());
        var zero=new CountedOffsetPageDto<String>(List.of(),0,2,Optional.of(1L),false,0,0);
        assertEquals(0,zero.totalPages());
        assertThrows(DtoContractException.class,()->new CountedOffsetPageDto<>(List.of("A"),0,2,Optional.of(1L),false,5,3));
        assertThrows(DtoContractException.class,()->new CountedOffsetPageDto<>(List.of(),0,2,Optional.of(1L),false,0,1));
    }
    @Test void cursorWireMetadataIsOpaqueAndDoesNotAuthenticateTheToken() {
        var source=CursorPage.of(new Values<>(List.of("a")),CursorRequest.first(1),Optional.of(PageCursor.of("opaque")));
        var dto=JsonPages.mapCursorPage(source,(value,index)->value);
        assertFalse(dto.toString().contains("opaque"));
        var wire=JsonPages.writeCursorPage(dto,JsonValues::scalarNode);
        assertFalse(wire.has("offset"));assertFalse(wire.has("totalElements"));assertEquals("opaque",wire.get("nextCursor").asText());
        assertEquals(dto,JsonPages.readCursorPage(wire,"$",(node,path)->JsonValues.scalar(node,path,String.class)));
        assertThrows(DtoContractException.class,()->new CursorPageDto<String>(List.of(),1,Optional.of("token")));
        assertThrows(DtoContractException.class,()->new CursorPageDto<>(List.of("x"),1,Optional.of(" ")));
        var finalPage=new CursorPageDto<String>(List.of(),2,Optional.empty());
        assertFalse(JsonPages.writeCursorPage(finalPage,JsonValues::scalarNode).has("nextCursor"));
    }
    @Test void defensiveCopyAndProjectionPreservePositionsAndRejectNulls() {
        var original=new ArrayList<>(List.of("a","a"));
        var dto=new OffsetPageDto<>(original,0,2,Optional.of(1L),true);
        original.clear();assertEquals(List.of("a","a"),dto.items());assertThrows(UnsupportedOperationException.class,()->dto.items().clear());
        assertThrows(DtoContractException.class,()->JsonPages.mapOffsetPage(dto,(value,index)->null));
        assertEquals(List.of("0:a","1:a"),JsonPages.mapOffsetPage(dto,(value,index)->index+":"+value).items());
        assertThrows(DtoContractException.class,()->new OffsetPageDto<>(List.of("x"),0,2,Optional.of(1L),true));
        assertThrows(DtoContractException.class,()->new OffsetPageDto<>(List.of(),1,2,Optional.of(1L),false));
    }
    @Test void wireInputRequiresExactNumbersAndValidMetadataButIgnoresAdditionalFields() throws Exception {
        var node=JSON.readTree("{\"items\":[],\"offset\":0,\"pageSize\":2,\"pageNumber\":1,\"hasNext\":false,\"totalElements\":0,\"totalPages\":0,\"extra\":true}");
        assertEquals(0,JsonPages.readCountedOffsetPage(node,"$",(n,p)->JsonValues.scalar(n,p,String.class)).totalElements());
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("totalElements",1.5);
        assertThrows(DtoContractException.class,()->JsonPages.readCountedOffsetPage(node,"$",(n,p)->JsonValues.scalar(n,p,String.class)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("totalElements",2);
        var error=assertThrows(DtoContractException.class,()->JsonPages.readCountedOffsetPage(node,"$.panel.page",(n,p)->JsonValues.scalar(n,p,String.class)));
        assertEquals("$.panel.page",error.path());
    }
}
