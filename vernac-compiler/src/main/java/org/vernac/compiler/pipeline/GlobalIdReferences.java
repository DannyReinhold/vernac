// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;
import java.nio.file.Path;
import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.symbols.*;
/** Explicit global metadata ID references, never tenant graph containment or cross-module SQL ownership. */
final class GlobalIdReferences {
    List<CompilerDiagnostic> validate(VernacProject project,Map<Path,FileTypeScope> scopes,Map<TypeNode,ResolvedType> types) {
        var errors=new ArrayList<CompilerDiagnostic>();var aggregates=new HashMap<String,AggregateNode>();var roots=new HashSet<String>();
        for(var source:project.sources())for(var d:source.unit().definitions()) {
            if(d instanceof AggregateNode a)aggregates.put(source.unit().namespace()+"."+a.name(),a);
            if(!source.external() && d instanceof RepositoryNode r)scopes.get(source.path()).resolve(r.aggregateName(),r.location()).type().filter(ResolvedType.Declared.class::isInstance)
                .map(ResolvedType.Declared.class::cast).ifPresent(t->roots.add(t.symbol().identity().qualifiedName()));
        }
        for(var source:project.sources())if(!source.external())for(var d:source.unit().definitions())if(d instanceof AggregateNode owner)for(var field:owner.fields()) {
            if(field.referenceTarget()==null)continue;
            var lookup=scopes.get(source.path()).resolve(field.referenceTarget(),field.location());errors.addAll(lookup.diagnostics());
            var target=lookup.type().orElse(null);var actual=types.get(field.type());
            var aggregate=target instanceof ResolvedType.Declared declared?aggregates.get(declared.symbol().identity().qualifiedName()):null;
            boolean valid=owner.global() && !field.isMutable() && actual instanceof ResolvedType.Declared id && id.symbol().kind()==TypeSymbol.Kind.ID
                && aggregate!=null && aggregate.global() && types.get(aggregate.idDefinition().type()) instanceof ResolvedType.Declared expected
                && id.symbol().identity().equals(expected.symbol().identity())
                && roots.contains(source.unit().namespace()+"."+owner.name())
                && target instanceof ResolvedType.Declared declared && roots.contains(declared.symbol().identity().qualifiedName());
            if(!valid)errors.add(CompilerDiagnostic.error(field.location(),"references requires an immutable nominal ID field between persisted global aggregates with the same target ID type."));
        }
        return errors;
    }
}
