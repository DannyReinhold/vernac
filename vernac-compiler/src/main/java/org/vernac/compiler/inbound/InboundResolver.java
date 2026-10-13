// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.inbound;

import java.util.*;
import java.util.regex.Pattern;
import com.palantir.javapoet.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.dto.*;
import org.vernac.compiler.mapping.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.language.VernacNames;
import static org.vernac.compiler.mapping.ResolvedMapping.*;

/** Uses the same scalar/VO conversions as outbound ACL and invokes declared usecase inputs. */
public final class InboundResolver {
    private final VernacProject project;
    private final Map<TypeNode,ResolvedType> types;
    private final List<CompilerDiagnostic> errors;
    private final Set<String> routes;
    public InboundResolver(VernacProject project, Map<TypeNode,ResolvedType> types,
            List<CompilerDiagnostic> errors, Set<String> routes) {
        this.project=project; this.types=types; this.errors=errors; this.routes=routes;
    }
    private void validateImplementation(String name, SourceLocation location) {
        if (!name.contains(".") || Arrays.stream(name.split("\\.")).anyMatch(p -> !VernacNames.isIdentifier(p)))
            error(location, "errors implemented by requires a fully qualified Java class name.");
    }
    private void validateRules(List<InboundHttpAdapterNode.ErrorRule> rules, FileTypeScope scope) {
        Set<String> seen = new HashSet<>();
        for (var rule : rules) {
            if (rule.status() < 400 || rule.status() > 599) error(rule.location(), "Error response status must be 400–599.");
            if (rule.code().map(String::isBlank).orElse(false) || rule.message().map(String::isBlank).orElse(false))
                error(rule.location(), "Public error code and message must not be blank.");
            String key = rule.selector()+":"+rule.responsibility()+":"+rule.category();
            if (rule.selector().equals("integration")) {
                try { org.vernac.runtime.ports.IntegrationCategory.valueOf(rule.category()); }
                catch (IllegalArgumentException e) { error(rule.location(), "Unknown integration category: "+rule.category()); }
            }
            if (rule.selector().equals("rejection")) {
                if (rule.rejectionType().isEmpty()) { error(rule.location(), "Rejection requires EnumType.CONSTANT."); continue; }
                var lookup = scope.resolve(rule.rejectionType().get().name(), rule.location()); errors.addAll(lookup.diagnostics());
                if (!(lookup.type().orElse(null) instanceof ResolvedType.Declared d) || d.symbol().kind()!=TypeSymbol.Kind.ENUM) {
                    error(rule.location(), "Rejection code must belong to a declared Vernac enum."); continue;
                }
                types.put(rule.rejectionType().get(), d);
                var symbol = d.symbol();
                boolean exists = project.sources().stream().filter(s -> s.unit().namespace().equals(symbol.identity().namespace()))
                    .flatMap(s -> s.unit().definitions().stream()).filter(n -> n instanceof ValueObjectNode v && v.name().equals(symbol.identity().name()))
                    .map(n -> (ValueObjectNode)n).flatMap(v -> v.enumConstants().stream()).anyMatch(c -> c.name().equals(rule.constant()));
                if (!exists) error(rule.location(), "Unknown rejection constant: "+rule.constant());
                key += ":"+symbol.identity().qualifiedName()+"."+rule.constant();
            }
            if (!seen.add(key)) error(rule.location(), "Duplicate inbound error selector: "+key);
        }
    }
    public ResolvedInbound resolve(InboundHttpAdapterNode adapter, String namespace, FileTypeScope scope) {
        validateRules(adapter.errorRules(), scope);
        for (var endpoint : adapter.endpoints()) {
            validateRules(endpoint.errorRules(), scope);
            endpoint.errorImplementation().ifPresent(n -> validateImplementation(n, endpoint.location()));
        }
        var schemas = new SchemaContracts().resolve(adapter.name(), adapter.schemas(), errors);
        String dtoPackage = namespace + ".adapter.inbound.http.dto";
        var resolver = new MappingResolver(project,types,errors).context(adapter.name(),namespace,scope,schemas,dtoPackage);
        Map<String,ResolvedInbound.Dependency> dependencies = new LinkedHashMap<>();
        for (var field:adapter.dependencies()) {
            var lookup=scope.resolve(field.type().name(),field.location()); errors.addAll(lookup.diagnostics());
            if (!VernacNames.isIdentifier(field.name()) || field.name().startsWith("__") || field.isMutable()
                    || field.type().isOptional() || !field.type().typeArguments().isEmpty()
                    || !(lookup.type().orElse(null) instanceof ResolvedType.Declared d) || d.symbol().kind()!=TypeSymbol.Kind.USE_CASE) {
                error(field.location(),"Inbound uses requires a non-optional usecase and a valid dependency name."); continue;
            }
            var symbol=((ResolvedType.Declared)lookup.type().orElseThrow()).symbol();
            var usecase=project.sources().stream().filter(s->s.unit().namespace().equals(symbol.identity().namespace()))
                    .flatMap(s->s.unit().definitions().stream()).filter(definition->definition instanceof UseCaseNode u && u.name().equals(symbol.identity().name()))
                    .map(definition->(UseCaseNode)definition).findFirst().orElseThrow();
            if(dependencies.putIfAbsent(field.name(),new ResolvedInbound.Dependency(field,symbol,usecase))!=null)
                error(field.location(),"Duplicate inbound dependency: "+field.name());
            if(!usecase.resultFields().isEmpty()) {
                var fields=new ArrayList<Field>();
                for(var f:usecase.resultFields()) if(types.containsKey(f.type())) {
                    var type=resolver.domain(types.get(f.type()),f.location());
                    if(type!=null)fields.add(new Field(f.name(),type,f.type().isOptional(),false));
                }
                var result=new Type(symbol.identity().qualifiedName()+".Result",
                        ClassName.get(symbol.identity().namespace()+".usecase",usecase.name()).nestedClass("Result"),Kind.RESULT,null,null,null,false);
                resolver.register(result,fields,symbol.identity().qualifiedName()+".Result",field.type().name()+".Result");
            }
        }
        var mappings=resolver.resolve(adapter.name(),adapter.mappings(),namespace,scope,schemas,dtoPackage);
        Map<String,ResolvedMapping> mappingNames=new LinkedHashMap<>(); mappings.forEach(m->mappingNames.put(m.source().name(),m));
        route(adapter.location(),adapter.base(),false);
        adapter.errorImplementation().ifPresent(n->{if(!n.contains(".") || Arrays.stream(n.split("\\.")).anyMatch(p->!VernacNames.isIdentifier(p)))
            error(adapter.location(),"errors implemented by requires a fully qualified Java class name.");});
        Set<String> names=new HashSet<>(); var endpoints=new ArrayList<ResolvedInbound.Endpoint>();
        for(var endpoint:adapter.endpoints()) {
            if(!VernacNames.isIdentifier(endpoint.name()) || endpoint.name().startsWith("__")
                    || Set.of("getClass","equals","hashCode","toString","wait","notify","notifyAll","clone","finalize").contains(endpoint.name())
                    || !names.add(endpoint.name())) error(endpoint.location(),"Invalid or duplicate inbound endpoint name: "+endpoint.name());
            route(endpoint.location(),endpoint.route(),true);
            String signature=endpoint.verb()+" "+join(adapter.base(),endpoint.route()).replaceAll("\\{[^}]+}","{}");
            if(!routes.add(signature))error(endpoint.location(),"Duplicate HTTP route: "+signature);
            if(endpoint.status()<200 || endpoint.status()>299)error(endpoint.location(),"Success response status must be 2xx.");
            if(endpoint.status()==204 || endpoint.status()==205) {
                if(endpoint.responseType().isPresent())error(endpoint.location(),"204/205 responses cannot contain a body.");
            }
            Map<String,List<Field>> groups=new LinkedHashMap<>();for(String group:List.of("path","query","header"))groups.put(group,new ArrayList<>());
            Set<String> bindings=new HashSet<>();Set<String> pathNames=new HashSet<>();
            for(var b:endpoint.bindings()) {
                var lookup=BuiltinTypes.find(b.type().name());
                if(lookup.isEmpty() || b.type().name().equals("EventId") || !b.type().typeArguments().isEmpty()) {
                    error(b.location(),"HTTP bindings require an approved wire scalar, not a domain type or collection.");continue;
                }
                if(!VernacNames.isIdentifier(b.name()) || b.name().startsWith("__"))error(b.location(),"Invalid binding name: "+b.name());
                if(b.externalName().isBlank() || b.externalName().codePoints().anyMatch(c->Character.isISOControl(c)))error(b.location(),"Invalid external binding name.");
                if(b.type().isOptional() && (b.kind().equals("path") || lookup.get().isPrimitive()))
                    error(b.location(),"Path values cannot be optional; optional scalar bindings require boxed types.");
                String key=b.kind()+":"+(b.kind().equals("header")?b.externalName().toLowerCase(Locale.ROOT):b.externalName());
                if(!bindings.add(key) || groups.get(b.kind()).stream().anyMatch(f->f.name().equals(b.name())))error(b.location(),"Duplicate HTTP binding: "+b.name());
                if(b.kind().equals("path"))pathNames.add(b.externalName());
                groups.get(b.kind()).add(new Field(b.name(),resolver.resolveType(new TypeNode(b.type().location(),b.type().name())),b.type().isOptional(),false));
            }
            Set<String> placeholders=new HashSet<>();var matcher=Pattern.compile("\\{([^}]+)}").matcher(endpoint.route());while(matcher.find())placeholders.add(matcher.group(1));
            if(!placeholders.equals(pathNames))error(endpoint.location(),"Every path placeholder requires exactly one matching path binding; no unused path bindings.");
            var rootFields=new ArrayList<Field>();
            for(var entry:groups.entrySet()) {
                var container=new Type("request:"+endpoint.name()+":"+entry.getKey(),ClassName.get(Object.class),Kind.RESULT,null,null,null,false);
                resolver.register(container,entry.getValue());rootFields.add(new Field(entry.getKey(),container,false,false));
            }
            if(endpoint.bodyType().isPresent()) {
                if(endpoint.verb().equals("GET"))error(endpoint.location(),"GET endpoints cannot declare a request body.");
                Type body=resolver.resolveType(endpoint.bodyType().get());
                if(body==null || body.kind()!=Kind.DTO)error(endpoint.location(),"Request body requires a non-optional adapter-local schema.");
                else rootFields.add(new Field("body",body,false,false));
            }
            Type request=new Type("request:"+endpoint.name(),ClassName.get(Object.class),Kind.RESULT,null,null,null,false);resolver.register(request,rootFields);
            var dependency=dependencies.get(endpoint.dependency());
            if(dependency==null) {error(endpoint.location(),"Unknown usecase dependency: "+endpoint.dependency());continue;}
            Map<String,MappingAssignmentNode> supplied=new LinkedHashMap<>();
            for(var a:endpoint.arguments())if(supplied.putIfAbsent(a.target(),a)!=null)error(a.location(),"Duplicate usecase argument: "+a.target());
            Set<String> parameters=new HashSet<>();var arguments=new ArrayList<ResolvedInbound.Argument>();
            for(var f:dependency.usecase().parameters()) {
                parameters.add(f.name());var a=supplied.get(f.name());
                if(a==null) {if(!f.type().isOptional())error(endpoint.location(),"Required usecase argument is not mapped: "+f.name());arguments.add(new ResolvedInbound.Argument(f,null,null));continue;}
                var pathParts=new ArrayList<String>();pathParts.add("source");pathParts.addAll(a.path());
                Path path=resolver.path(request,new MappingAssignmentNode(a.location(),a.target(),pathParts,a.mapping()));
                if(path==null)continue;
                if(path.patch()) {error(a.location(),"PATCH instructions require an explicit mapping to an application value.");continue;}
                if(path.optional() && !f.type().isOptional())error(a.location(),"An optional transport value cannot satisfy required usecase argument: "+f.name());
                Type target=types.containsKey(f.type())?resolver.domain(types.get(f.type()),f.location()):null;
                if(target==null) {error(a.location(),"Usecase argument type could not be resolved: "+f.name());continue;}
                Conversion conversion=a.mapping().isPresent()?resolver.named(path.result(),target,a.mapping().get(),a.location()):resolver.automatic(path.result(),target,new HashSet<>());
                if(conversion==null)error(a.location(),"No exact input conversion for "+f.name()+". Specify using <mapping>.");
                else arguments.add(new ResolvedInbound.Argument(f,path,conversion));
            }
            for(var a:supplied.values())if(!parameters.contains(a.target()))error(a.location(),"Unknown usecase argument: "+a.target());
            Type result=null;boolean optional=false;
            if(!dependency.usecase().resultFields().isEmpty())result=resolver.resolveType(new TypeNode(endpoint.location(),dependency.symbol().identity().qualifiedName()+".Result"));
            else if(dependency.usecase().resultType().isPresent()) {
                var t=dependency.usecase().resultType().get();optional=t.isOptional();
                if(types.containsKey(t))result=resolver.domain(types.get(t),t.location());
            }
            ResolvedMapping response=null;
            if(endpoint.responseType().isPresent()) {
                Type responseType=resolver.resolveType(endpoint.responseType().get());
                response=mappingNames.get(endpoint.responseMapping().orElse(""));
                if(result==null || responseType==null || responseType.kind()!=Kind.DTO || response==null
                        || !response.input().identity().equals(result.identity()) || !response.target().identity().equals(responseType.identity()))
                    error(endpoint.location(),"Response requires a local DTO and a mapping from the exact declared usecase result.");
            } else if(result!=null)error(endpoint.location(),"A value-returning usecase requires an explicit response mapping.");
            if(optional && endpoint.absentStatus().isEmpty())error(endpoint.location(),"Optional usecase results require on absent respond <status>.");
            if(endpoint.absentStatus().isPresent() && (!optional || !Set.of(204,404,410).contains(endpoint.absentStatus().get())))
                error(endpoint.location(),"Absent response requires an optional usecase result and status 204, 404 or 410.");
            endpoints.add(new ResolvedInbound.Endpoint(endpoint,dependency,arguments,result,response));
        }
        if(adapter.endpoints().isEmpty())error(adapter.location(),"An HTTP inbound adapter requires an endpoint.");
        return new ResolvedInbound(schemas,mappings,List.copyOf(dependencies.values()),List.copyOf(endpoints));
    }
    public static String join(String base,String route) {
        String result=base.replaceAll("/+$","")+(route.equals("/")?"":route);
        return result.isEmpty()?"/":result;
    }
    private void route(SourceLocation at,String path,boolean placeholders) {
        if(!path.startsWith("/") || path.contains("//") || path.contains("?") || path.contains("#") || path.contains("*") || path.contains("\\")
                || Arrays.stream(path.split("/")).anyMatch(s->s.equals(".") || s.equals(".."))
                || path.codePoints().anyMatch(c->Character.isISOControl(c) || Character.isWhitespace(c))
                || (!placeholders && (path.contains("{") || path.contains("}"))))error(at,"HTTP paths must be absolute application paths without query, fragment or dot segments.");
        if(placeholders) {
            String stripped=path.replaceAll("\\{[^{}]+}","");
            if(stripped.contains("{") || stripped.contains("}") || Pattern.compile("\\{[^}]*:[^}]*}").matcher(path).find())error(at,"Malformed path placeholder.");
        }
    }
    private void error(SourceLocation at,String message) {errors.add(CompilerDiagnostic.error(at,message));}
}
