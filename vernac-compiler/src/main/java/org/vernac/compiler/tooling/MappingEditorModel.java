// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;
import java.nio.file.Path;
import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.mapping.*;
import org.vernac.compiler.pipeline.*;

/** Resolves mapping types/fields even while the user's assignments are incomplete. */
public final class MappingEditorModel {
    private final ResolvedProject base;
    public MappingEditorModel(VernacProject original) {
        List<VernacSourceFile> sources=new ArrayList<>();
        for(var source:original.sources()) {
            List<TopLevelDefinition> definitions=source.unit().definitions().stream().map(d -> d instanceof OutboundAdapterNode a
                ? (TopLevelDefinition)new OutboundAdapterNode(a.location(),a.name(),a.portType(),a.dependencies(),a.javaDependencies(),a.javaImports(),a.methods(),a.schemas(),List.of()):d).toList();
            var unit=source.unit();sources.add(new VernacSourceFile(source.path(),new CompilationUnitNode(unit.location(),unit.namespace(),unit.imports(),definitions)));
        }
        base=new ProjectTypeResolver().resolve(new VernacProject(original.sourceRoot(),sources,original.symbols()));
    }
    public MappingResolver resolver(Path path,String adapterName) {
        var source=base.project().sources().stream().filter(s->s.path().equals(path)).findFirst().orElseThrow();
        var adapter=source.unit().definitions().stream().filter(d->d instanceof OutboundAdapterNode a && a.name().equals(adapterName)).map(OutboundAdapterNode.class::cast).findFirst().orElseThrow();
        return new MappingResolver(base.project(),base.types(),new ArrayList<>()).context(adapter,source.unit().namespace(),base.scopes().get(path),base.schemas().getOrDefault(adapter,List.of()));
    }
    public Map<String,SourceLocation> fields(Path path,String adapterName,TypeNode root,List<String> prefix) {
        var resolver=resolver(path,adapterName);var type=resolver.resolveType(root);
        for(String step:prefix) {
            if(type==null)return Map.of();
            var next=resolver.fields(type).stream().filter(f->f.name().equals(step)).findFirst();
            if(next.isEmpty() || next.get().patch())return Map.of();type=next.get().type();
        }
        if(type==null)return Map.of();
        Map<String,SourceLocation> result=new TreeMap<>();
        for(var field:resolver.fields(type)) {
            SourceLocation location;
            if(type.schema()!=null) location=type.schema().fields().stream().filter(f->f.source().name().equals(field.name())).findFirst().orElseThrow().source().location();
            else if(type.symbol()!=null) {
                var symbol=type.symbol();location=symbol.location();
                for(var source:base.project().sources()) if(source.unit().namespace().equals(symbol.identity().namespace()))
                    for(var value:source.unit().valueObjects()) if(value.name().equals(symbol.identity().name()))
                        for(var member:value.fields())if(member.name().equals(field.name()))location=member.location();
            } else continue;
            result.put(field.name(),location);
        }
        return result;
    }
}
