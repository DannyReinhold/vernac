// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.vernac.runtime.DomainList;
import org.vernac.runtime.pagination.*;
import org.vernac.runtime.json.*;

/** Explicit page codecs and metadata-preserving ACL projection, without global Jackson configuration. */
public final class JsonPages {
    private JsonPages() { }
    private record Items<T>(List<T> asList) implements DomainList<T> {
        public Iterator<T> iterator() { return asList.iterator(); }
    }
    private static Optional<Long> number(OptionalLong value) { return value.isPresent()?Optional.of(value.getAsLong()):Optional.empty(); }
    static <T> void checkOffsetPage(List<T> items,long offset,int size,Optional<Long> pageNumber,boolean hasNext) {
        try {
            var page=OffsetPage.of(new Items<>(items),OffsetRequest.ofOffset(offset,size),hasNext);
            if(!number(page.pageNumber()).equals(JsonValues.require(pageNumber,"$.pageNumber"))) invalid("Inconsistent pageNumber");
        } catch(DtoContractException ex) { throw ex; }
        catch(RuntimeException ex) { invalid("Invalid offset page metadata"); }
    }
    static <T> void checkCountedOffsetPage(List<T> items,long offset,int size,Optional<Long> pageNumber,boolean hasNext,long total,long totalPages) {
        try {
            var page=CountedOffsetPage.of(new Items<>(items),OffsetRequest.ofOffset(offset,size),total);
            if(!number(page.pageNumber()).equals(JsonValues.require(pageNumber,"$.pageNumber")) || hasNext!=page.hasNext() || totalPages!=page.totalPages()) invalid("Inconsistent counted page metadata");
        } catch(DtoContractException ex) { throw ex; }
        catch(RuntimeException ex) { invalid("Invalid counted offset page metadata"); }
    }
    static <T> void checkCursorPage(List<T> items,int size,Optional<String> nextCursor) {
        try { CursorPage.of(new Items<>(items),CursorRequest.first(size),JsonValues.require(nextCursor,"$.nextCursor").map(PageCursor::of)); }
        catch(RuntimeException ex) { invalid("Invalid cursor page metadata"); }
    }
    private static void invalid(String message) { throw new DtoContractException("$",message); }
    private static <T,R> List<R> map(Iterable<T> values,BiFunction<T,Integer,R> mapper) {
        List<R> result=new ArrayList<>(); int index=0;
        for(T value:values) result.add(JsonValues.require(mapper.apply(value,index++),"$.items"));
        return List.copyOf(result);
    }
    public static <T,C extends DomainList<T>,R> OffsetPageDto<R> mapOffsetPage(OffsetPage<T,C> page,BiFunction<T,Integer,R> mapper) {
        return new OffsetPageDto<>(map(page.items(),mapper),page.offset(),page.pageSize(),number(page.pageNumber()),page.hasNext());
    }
    public static <T,C extends DomainList<T>,R> CountedOffsetPageDto<R> mapCountedOffsetPage(CountedOffsetPage<T,C> page,BiFunction<T,Integer,R> mapper) {
        return new CountedOffsetPageDto<>(map(page.items(),mapper),page.offset(),page.pageSize(),number(page.pageNumber()),page.hasNext(),page.totalElements(),page.totalPages());
    }
    public static <T,C extends DomainList<T>,R> CursorPageDto<R> mapCursorPage(CursorPage<T,C> page,BiFunction<T,Integer,R> mapper) {
        return new CursorPageDto<>(map(page.items(),mapper),page.pageSize(),page.nextCursor().map(PageCursor::value));
    }
    public static <T,R> OffsetPageDto<R> mapOffsetPage(OffsetPageDto<T> page,BiFunction<T,Integer,R> mapper) {
        return new OffsetPageDto<>(map(page.items(),mapper),page.offset(),page.pageSize(),page.pageNumber(),page.hasNext());
    }
    public static <T,R> CountedOffsetPageDto<R> mapCountedOffsetPage(CountedOffsetPageDto<T> page,BiFunction<T,Integer,R> mapper) {
        return new CountedOffsetPageDto<>(map(page.items(),mapper),page.offset(),page.pageSize(),page.pageNumber(),page.hasNext(),page.totalElements(),page.totalPages());
    }
    public static <T,R> CursorPageDto<R> mapCursorPage(CursorPageDto<T> page,BiFunction<T,Integer,R> mapper) {
        return new CursorPageDto<>(map(page.items(),mapper),page.pageSize(),page.nextCursor());
    }
    public static <T> OffsetPageDto<T> readOffsetPage(@Nullable JsonNode node,String path,BiFunction<JsonNode,String,T> reader) {
        var object=JsonValues.object(node,path);
        return checkedOffset(JsonValues.list(object.get("items"),path+".items",reader),required(object,"offset",path,Long.class),required(object,"pageSize",path,Integer.class),
                Optional.ofNullable(JsonValues.optional(object.get("pageNumber"),path+".pageNumber",(n,p)->JsonValues.scalar(n,p,Long.class))),required(object,"hasNext",path,Boolean.class),path);
    }
    public static <T> CountedOffsetPageDto<T> readCountedOffsetPage(@Nullable JsonNode node,String path,BiFunction<JsonNode,String,T> reader) {
        var object=JsonValues.object(node,path);
        return checkedCounted(JsonValues.list(object.get("items"),path+".items",reader),required(object,"offset",path,Long.class),required(object,"pageSize",path,Integer.class),
                Optional.ofNullable(JsonValues.optional(object.get("pageNumber"),path+".pageNumber",(n,p)->JsonValues.scalar(n,p,Long.class))),required(object,"hasNext",path,Boolean.class),
                required(object,"totalElements",path,Long.class),required(object,"totalPages",path,Long.class),path);
    }
    public static <T> CursorPageDto<T> readCursorPage(@Nullable JsonNode node,String path,BiFunction<JsonNode,String,T> reader) {
        var object=JsonValues.object(node,path);
        return checkedCursor(JsonValues.list(object.get("items"),path+".items",reader),required(object,"pageSize",path,Integer.class),
                Optional.ofNullable(JsonValues.optional(object.get("nextCursor"),path+".nextCursor",(n,p)->JsonValues.scalar(n,p,String.class))),path);
    }
    private static <T> T scoped(String path,java.util.function.Supplier<T> factory) {
        try { return factory.get(); }
        catch(DtoContractException error) {
            String reason=error.getMessage().substring(error.path().length()+2);
            throw new DtoContractException(path+error.path().substring(1),reason);
        }
    }
    private static <T> OffsetPageDto<T> checkedOffset(List<T> items,long offset,int size,Optional<Long> number,boolean hasNext,String path) {
        return scoped(path,()->new OffsetPageDto<>(items,offset,size,number,hasNext));
    }
    private static <T> CountedOffsetPageDto<T> checkedCounted(List<T> items,long offset,int size,Optional<Long> number,boolean hasNext,long total,long totalPages,String path) {
        return scoped(path,()->new CountedOffsetPageDto<>(items,offset,size,number,hasNext,total,totalPages));
    }
    private static <T> CursorPageDto<T> checkedCursor(List<T> items,int size,Optional<String> cursor,String path) {
        return scoped(path,()->new CursorPageDto<>(items,size,cursor));
    }
    private static <T> T required(ObjectNode object,String name,String path,Class<T> type) { return JsonValues.scalar(object.get(name),path+"."+name,type); }
    private static <T> ObjectNode offset(List<T> items,long offset,int size,Optional<Long> number,boolean hasNext,Function<T,JsonNode> writer) {
        var object=JsonNodeFactory.instance.objectNode();
        object.set("items",JsonValues.array(items,writer)); object.put("offset",offset); object.put("pageSize",size);
        number.ifPresent(value->object.put("pageNumber",value)); object.put("hasNext",hasNext); return object;
    }
    public static <T> ObjectNode writeOffsetPage(OffsetPageDto<T> page,Function<T,JsonNode> writer) {
        return offset(page.items(),page.offset(),page.pageSize(),page.pageNumber(),page.hasNext(),writer);
    }
    public static <T> ObjectNode writeCountedOffsetPage(CountedOffsetPageDto<T> page,Function<T,JsonNode> writer) {
        var object=offset(page.items(),page.offset(),page.pageSize(),page.pageNumber(),page.hasNext(),writer);
        object.put("totalElements",page.totalElements()); object.put("totalPages",page.totalPages()); return object;
    }
    public static <T> ObjectNode writeCursorPage(CursorPageDto<T> page,Function<T,JsonNode> writer) {
        var object=JsonNodeFactory.instance.objectNode(); object.set("items",JsonValues.array(page.items(),writer)); object.put("pageSize",page.pageSize());
        page.nextCursor().ifPresent(value->object.put("nextCursor",value)); return object;
    }
}
