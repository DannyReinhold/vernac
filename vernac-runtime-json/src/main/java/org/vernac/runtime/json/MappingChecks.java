// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json;
import java.util.*;
import java.util.function.*;
import org.jspecify.annotations.*;
import org.vernac.runtime.DomainValidationException;
/** Generated conversion boundaries; unrelated programming exceptions propagate unchanged. */
@NullMarked
public final class MappingChecks {
    private MappingChecks() { }
    public static <T extends @Nullable Object> T field(String mapping,String path,Supplier<T> conversion) {
        try {return conversion.get();}
        catch(MappingContractException ex) {
            if(mapping.equals(ex.mapping()) && (ex.path().equals(path) || ex.path().startsWith(path+".") || ex.path().startsWith(path+"["))) throw ex;
            String nested=ex.path().startsWith("$")?ex.path().substring(1):"."+ex.path();
            throw new MappingContractException(mapping,path+nested,ex);
        } catch(DomainValidationException | DtoContractException ex) {
            throw new MappingContractException(mapping,path,ex);
        }
    }
    public static <T> T result(String mapping,String path,Supplier<@Nullable T> conversion) {
        return field(mapping,path,()->{
            T value=conversion.get();
            if(value==null)throw new MappingContractException(mapping,path,null);
            return value;
        });
    }
    public static <T,R> List<R> elements(Iterable<T> values,BiFunction<T,Integer,R> conversion) {
        List<R> result=new ArrayList<>();int index=0;
        for(T value:values) result.add(conversion.apply(value,index++));
        return List.copyOf(result);
    }
}
