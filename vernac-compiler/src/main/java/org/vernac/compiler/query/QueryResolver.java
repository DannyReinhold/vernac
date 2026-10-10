// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.query;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import javax.lang.model.SourceVersion;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;

/** Shared by project compilation and LSP diagnostics; never guesses Java or SQL types. */
public final class QueryResolver {
    public static final Set<String> ORDERED = Set.of("byte","Byte","short","Short","int","Integer",
            "long","Long","char","Character","String","BigInteger","BigDecimal","Year","LocalDate",
            "LocalTime","LocalDateTime","Instant","Duration","YearMonth","MonthDay");
    private final Map<String,TopLevelDefinition> definitions = new HashMap<>();
    private final Map<TypeNode,ResolvedType> types;
    private final List<CompilerDiagnostic> diagnostics;
    public QueryResolver(VernacProject project, Map<TypeNode,ResolvedType> types, List<CompilerDiagnostic> diagnostics) {
        this.types=types; this.diagnostics=diagnostics;
        for(var source:project.sources()) for(var d:source.unit().definitions()) {
            if(d instanceof AggregateNode a) definitions.put(source.unit().namespace()+"."+a.name(),a);
            if(d instanceof ValueObjectNode v) definitions.put(source.unit().namespace()+"."+v.name(),v);
        }
    }
    public Map<RepositoryMethodNode,ResolvedQuery> resolve(VernacProject project, Map<Path,FileTypeScope> scopes) {
        var result=new LinkedHashMap<RepositoryMethodNode,ResolvedQuery>();
        for(var source:project.sources()) for(var d:source.unit().definitions()) if(d instanceof RepositoryNode repo) {
            var scope=scopes.get(source.path());
            var lookup=scope.resolve(repo.aggregateName(),repo.location());
            diagnostics.addAll(lookup.diagnostics());
            if(!(lookup.type().orElse(null) instanceof ResolvedType.Declared target)
                    || target.symbol().kind()!=TypeSymbol.Kind.AGGREGATE) {
                error(repo.location(),"Repository requires an aggregate root."); continue;
            }
            AggregateNode aggregate=(AggregateNode)definitions.get(target.symbol().identity().qualifiedName());
            if(repo.customPackage().isPresent()) error(repo.location(),"Repository packages derive from their namespace.");
            Set<String> names=new HashSet<>(Set.of("byId","save","delete","getClass","hashCode","equals","toString","wait","notify","notifyAll","finalize","clone","rootMapping","entityMappings","tables"));
            for(var method:repo.methods()) {
                int before=diagnostics.size();
                if(method.name().matches("valueMapping[0-9]+") || !SourceVersion.isIdentifier(method.name()) || SourceVersion.isKeyword(method.name()) || !names.add(method.name()))
                    error(method.location(),"Invalid, duplicate or reserved repository method: "+method.name());
                if(method.isCustom()) { error(method.location(),"Custom JDBC queries are deferred; declare a find method with where/order by."); continue; }
                ResolvedType returns=resolveType(scope,method.returnType());
                boolean singleton=method.returnType().isOptional();
                boolean list=false;
                if(singleton) {
                    if(!target.equals(returns)) error(method.location(),"Optional query result must be the repository aggregate: "+aggregate.name()+"?");
                    if(!method.orders().isEmpty()) error(method.location(),"Optional singleton queries cannot use order by; multiple matches are an error.");
                } else {
                    var collection=aggregate.collection();
                    String expected=collection.map(c->target.symbol().identity().namespace()+"."+c.nameFor(aggregate.name())).orElse("");
                    if(!(returns instanceof ResolvedType.Declared rt) || rt.symbol().kind()!=TypeSymbol.Kind.COLLECTION
                            || !rt.symbol().identity().qualifiedName().equals(expected))
                        error(method.location(),"Query result requires an explicitly declared list or set of "+aggregate.name()+". Declare it on the aggregate, or use "+aggregate.name()+"?.");
                    list=collection.isPresent() && collection.get().kind()==CollectionDefinitionNode.Kind.LIST;
                    if(!list && !method.orders().isEmpty()) error(method.location(),"A set result cannot preserve order by; use a list result.");
                    if(list && method.orders().isEmpty()) diagnostics.add(CompilerDiagnostic.warning(method.location(),"List query has no order by; result order is unspecified."));
                }
                Map<String,FieldNode> params=new HashMap<>();
                for(var p:method.parameters()) {
                    resolveType(scope,p.type());
                    if(p.name().startsWith("__vernac") || !p.hasExplicitName() || !SourceVersion.isIdentifier(p.name()) || SourceVersion.isKeyword(p.name()) || params.putIfAbsent(p.name(),p)!=null)
                        error(p.location(),"Query parameters require explicit, valid and unique names.");
                    if(p.type().isOptional() || p.isMutable()) error(p.location(),"Query parameters must be non-optional and immutable.");
                }
                List<ResolvedQuery.Predicate> predicates=new ArrayList<>();
                Set<String> used=new HashSet<>();
                for(var p:method.predicates()) {
                    var f=field(aggregate,p.field(),p.location());
                    if(f==null) continue;
                    FieldNode param=null;
                    if(p.operator().equals("absent") || p.operator().equals("present")) {
                        if(!f.optional()) error(p.location(),"is absent/is present requires an optional field: "+p.field());
                    } else {
                        param=params.get(p.parameter()); used.add(p.parameter());
                        if(param==null) error(p.location(),"Unknown query parameter: "+p.parameter());
                        else if(!Objects.equals(f.type(),types.get(param.type()))) error(p.location(),"Query field and parameter must have the same Vernac type: "+p.field()+" and :"+p.parameter());
                        if(!Set.of("=","!=").contains(p.operator()) && !ORDERED.contains(f.scalar()))
                            error(p.location(),"Range comparison is not supported for "+p.field()+" ("+f.scalar()+").");
                    }
                    predicates.add(new ResolvedQuery.Predicate(f,p.operator(),param));
                }
                for(var p:method.parameters()) if(!used.contains(p.name())) error(p.location(),"Unused query parameter: "+p.name());
                List<ResolvedQuery.Order> orders=new ArrayList<>();
                Set<String> sorted=new HashSet<>();
                for(var o:method.orders()) {
                    var f=field(aggregate,o.field(),o.location());
                    if(f==null) continue;
                    if(!ORDERED.contains(f.scalar())) error(o.location(),"Ordering is not supported for "+o.field()+" ("+f.scalar()+").");
                    if(!sorted.add(o.field())) error(o.location(),"Duplicate sort field: "+o.field());
                    orders.add(new ResolvedQuery.Order(f,o.descending()));
                }
                if(returns!=null && diagnostics.subList(before,diagnostics.size()).stream().noneMatch(x->x.severity()==CompilerDiagnostic.Severity.ERROR))
                    result.put(method,new ResolvedQuery(method,returns,singleton,List.copyOf(predicates),List.copyOf(orders)));
            }
        }
        return result;
    }
    private ResolvedType resolveType(FileTypeScope scope, TypeNode t) {
        var lookup=scope.resolve(t.name(),t.location()); diagnostics.addAll(lookup.diagnostics());
        if(!t.typeArguments().isEmpty()) error(t.location(),"Use a declared aggregate collection or Aggregate?, not Java generics.");
        lookup.type().ifPresent(v->types.put(t,v));
        return lookup.type().orElse(null);
    }
    private ResolvedQuery.Field field(AggregateNode a,String name,SourceLocation location) {
        if(name.equals("createdAt") || name.equals("updatedAt")) return new ResolvedQuery.Field(name,new ResolvedType.Builtin(Instant.class),false,"Instant","");
        if(name.equals("id")) return new ResolvedQuery.Field(name,types.get(a.idDefinition().type()),false,"UUID","value");
        var f=a.fields().stream().filter(v->v.name().equals(name)).findFirst().orElse(null);
        if(f==null) { error(location,"Unknown aggregate query field: "+name); return null; }
        ResolvedType t=types.get(f.type());
        if(t instanceof ResolvedType.Declared d) {
            if(d.symbol().kind()==TypeSymbol.Kind.ID) return new ResolvedQuery.Field(name,t,f.type().isOptional(),"UUID","value");
            if(d.symbol().kind()==TypeSymbol.Kind.ENUM) return new ResolvedQuery.Field(name,t,f.type().isOptional(),"enum","");
            if(d.symbol().kind()==TypeSymbol.Kind.VALUE_OBJECT) {
                var vo=(ValueObjectNode)definitions.get(d.symbol().identity().qualifiedName());
                if(vo.fields().size()==1 && !vo.fields().getFirst().type().isOptional()
                        && types.get(vo.fields().getFirst().type()) instanceof ResolvedType.Builtin b)
                    return new ResolvedQuery.Field(name,t,f.type().isOptional(),b.javaType().getSimpleName(),vo.fields().getFirst().name());
            }
        }
        error(location,"Query field requires an ID, enum or single-value object with one required built-in scalar: "+name);
        return null;
    }
    private void error(SourceLocation at,String message) { diagnostics.add(CompilerDiagnostic.error(at,message)); }
}
