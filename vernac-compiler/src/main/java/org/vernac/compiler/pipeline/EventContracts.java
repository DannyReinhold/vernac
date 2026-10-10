// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;
import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.util.MemberNames;
import org.vernac.language.VernacNames;

final class EventContracts {
    void validate(EventNode e,FileTypeScope scope,Map<TypeNode,ResolvedType> types,List<CompilerDiagnostic> errors,VernacProject project,String namespace) {
        if(e.recorded()) errors.add(CompilerDiagnostic.error(e.location(),"recorded events require the upcoming relational event persistence implementation; no silent in-memory fallback."));
        errors.addAll(MemberNames.duplicates(e.fields(),"event field",""));
        for(var f:e.fields()) {
            if(!VernacNames.isIdentifier(f.name()) || f.isMutable() || f.name().startsWith("__") || Set.of("create","reconstitute","eventId","occurredAt","getClass","hashCode","toString","notify","notifyAll","wait","clone","finalize","validate").contains(f.name()))
                errors.add(CompilerDiagnostic.error(f.location(),"Invalid, mutable or reserved event field: "+f.name()));
            var lookup=scope.resolve(f.type().name(),f.location());errors.addAll(lookup.diagnostics());
            var t=lookup.type().orElse(null);
            boolean allowed=t instanceof ResolvedType.Declared d && (Set.of(TypeSymbol.Kind.ID,TypeSymbol.Kind.VALUE_OBJECT,TypeSymbol.Kind.ENUM).contains(d.symbol().kind())
                    || d.symbol().kind()==TypeSymbol.Kind.COLLECTION && CollectionTypes.find(project,d.symbol().identity()).map(c->c.valueElements()).orElse(false))
                    || t instanceof ResolvedType.Builtin builtin && builtin.javaType()==org.vernac.runtime.EventId.class;
            if(!allowed || !f.type().typeArguments().isEmpty()) errors.add(CompilerDiagnostic.error(f.location(),"Event payloads require IDs, value objects, enums or their declared value collections."));
            if(t!=null) types.put(f.type(),t);
        }
    }
    void validate(ListenerNode l,FileTypeScope scope,Map<TypeNode,ResolvedType> types,List<CompilerDiagnostic> errors,VernacProject project,String namespace) {
        if(l.deferred()) errors.add(CompilerDiagnostic.error(l.location(),"deferred listeners require the upcoming durable delivery implementation; no synchronous fallback."));
        var lookup=scope.resolve(l.eventType().name(),l.location());errors.addAll(lookup.diagnostics());
        if(l.eventType().isOptional() || !l.eventType().typeArguments().isEmpty() || !(lookup.type().orElse(null) instanceof ResolvedType.Declared d) || d.symbol().kind()!=TypeSymbol.Kind.EVENT)
            errors.add(CompilerDiagnostic.error(l.location(),"Listener on requires a non-optional Event type."));
        new UseCaseContracts().validate(l.operation(),scope,types,errors,project,namespace,true);
    }
}
