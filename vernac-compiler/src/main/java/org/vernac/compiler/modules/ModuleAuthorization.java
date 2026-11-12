// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import static org.vernac.compiler.modules.ModuleDescriptor.*;
import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.language.VernacNames;

/** Data-only security metadata, closure, validation and review boundaries. */
final class ModuleAuthorization {
    private ModuleAuthorization() {}

    static void validateShape(String mode,String permission,String member,String resource,String resourceType,List<Authorization> children) {
        if (!Set.of("OMITTED","PUBLIC","AUTHENTICATED","PERMISSION","ALL","ANY").contains(mode))
            throw new IllegalArgumentException("Unknown module authorization mode: " + mode);
        if (mode.equals("PERMISSION")) {
            if (!qualified(permission) || !VernacNames.isIdentifier(member) || !children.isEmpty()
                    || resource.isEmpty()!=resourceType.isEmpty()
                    || !resource.isEmpty() && (!VernacNames.isIdentifier(resource) || !qualified(resourceType)))
                throw new IllegalArgumentException("Invalid module permission requirement");
        } else if (!permission.isEmpty() || !member.isEmpty() || !resource.isEmpty() || !resourceType.isEmpty())
            throw new IllegalArgumentException("Unexpected fields in module authorization mode " + mode);
        if (Set.of("ALL","ANY").contains(mode)) {
            if (children.isEmpty() || children.stream().anyMatch(c -> Set.of("OMITTED","PUBLIC","AUTHENTICATED").contains(c.mode())))
                throw new IllegalArgumentException("Authorization groups require permission/group children");
            var leaves = new HashSet<Authorization>();
            for (var child:children) if (child.mode().equals("PERMISSION") && !leaves.add(child))
                throw new IllegalArgumentException("Duplicate sibling module permission requirement");
        } else if (!children.isEmpty()) throw new IllegalArgumentException("Unexpected authorization children");
    }
    private static boolean qualified(String name) { return name.contains(".") && VernacNames.isNamespace(name); }

    static Authorization encode(Optional<AuthorizationNode> node,ResolvedProject project,UseCaseNode operation) {
        return node.map(n -> encode(n,project,operation)).orElseGet(Authorization::omitted);
    }
    private static Authorization encode(AuthorizationNode node,ResolvedProject project,UseCaseNode operation) {
        if (node instanceof AuthorizationNode.Mode mode)
            return new Authorization(mode.authenticated()?"AUTHENTICATED":"PUBLIC","","","","",List.of());
        if (node instanceof AuthorizationNode.Permission permission) {
            var declared=(ResolvedType.Declared)project.typeOf(permission.type());
            String inputType=permission.resource().map(name -> operation.parameters().stream().filter(f->f.name().equals(name)).findFirst().orElseThrow())
                    .map(f -> ((ResolvedType.Declared)project.typeOf(f.type())).symbol().identity().qualifiedName()).orElse("");
            return new Authorization("PERMISSION",declared.symbol().identity().qualifiedName(),permission.member(),permission.resource().orElse(""),inputType,List.of());
        }
        var group=(AuthorizationNode.Group)node;
        return new Authorization(group.all()?"ALL":"ANY","","","","",group.children().stream().map(c->encode(c,project,operation)).toList());
    }
    static Optional<AuthorizationNode> decode(Authorization requirement,SourceLocation at) {
        return requirement.mode().equals("OMITTED")?Optional.empty():Optional.of(node(requirement,at));
    }
    private static AuthorizationNode node(Authorization requirement,SourceLocation at) {
        return switch(requirement.mode()) {
            case "PUBLIC","AUTHENTICATED" -> new AuthorizationNode.Mode(at,requirement.mode().equals("AUTHENTICATED"));
            case "PERMISSION" -> new AuthorizationNode.Permission(at,new TypeNode(at,requirement.permission()),requirement.member(),requirement.resource().isEmpty()?Optional.empty():Optional.of(requirement.resource()));
            case "ALL","ANY" -> new AuthorizationNode.Group(at,requirement.mode().equals("ALL"),requirement.children().stream().map(c->node(c,at)).toList());
            default -> throw new IllegalArgumentException("Invalid nested authorization mode");
        };
    }
    static Set<String> permissionTypes(Authorization requirement) {
        var identities=new TreeSet<String>();
        if (requirement.mode().equals("PERMISSION")) identities.add(requirement.permission());
        requirement.children().forEach(c -> identities.addAll(permissionTypes(c))); return identities;
    }
    static List<UseCaseAccess> collect(ResolvedProject project) {
        var contracts=new ArrayList<UseCaseAccess>();
        for(var source:project.project().sources()) if(!source.external()) for(var definition:source.unit().definitions())
            if(definition instanceof UseCaseNode operation) contracts.add(new UseCaseAccess(source.unit().namespace()+"."+operation.name(),
                    operation.parameters().stream().map(f->new Field(f.name(),inputType(f,project))).toList(),
                    encode(operation.authorization(),project,operation)));
        return contracts.stream().sorted(Comparator.comparing(UseCaseAccess::identity)).toList();
    }
    private static Type inputType(FieldNode field,ResolvedProject project) {
        var resolved=project.typeOf(field.type());
        String identity=resolved instanceof ResolvedType.Declared d?d.symbol().identity().qualifiedName()
                :((ResolvedType.Page)resolved).collection().symbol().identity().qualifiedName();
        return new Type(identity,field.type().isOptional(),field.type().pageKind().name());
    }
    static List<UseCaseAccess> defaultExportAccess(List<Definition> definitions) {
        return definitions.stream().filter(d->d.kind().equals("USE_CASE")).map(d->new UseCaseAccess(d.identity(),inputs(d),d.authorization())).toList();
    }
    static List<UseCaseAccess> defaultAccess(List<Component> components,List<Definition> definitions) {
        var exported=new HashMap<String,UseCaseAccess>(); defaultExportAccess(definitions).forEach(a->exported.put(a.identity(),a));
        return components.stream().filter(c->c.kind().equals("USE_CASE"))
                .map(c->exported.getOrDefault(c.identity(),new UseCaseAccess(c.identity(),List.of(),Authorization.omitted()))).toList();
    }
    static List<Field> inputs(Definition definition) {
        return definition.methods().stream().filter(m->m.name().equals("execute")).findFirst().orElseThrow().parameters();
    }
    static void validate(List<ModuleDescriptor> modules) {
        var exported=new HashMap<String,Definition>();
        modules.forEach(m->m.exports().forEach(d->exported.put(d.identity(),d)));
        for(var module:modules) {
            var access=new HashMap<String,UseCaseAccess>();
            for(var entry:module.usecaseAccess()) {
                if (!qualified(entry.identity()) || access.putIfAbsent(entry.identity(),entry)!=null
                        || module.components().stream().noneMatch(c->c.kind().equals("USE_CASE")&&c.identity().equals(entry.identity())))
                    throw new IllegalArgumentException("Invalid/duplicate usecase authorization inventory: "+entry.identity());
                validateInputs(entry);
                validateReferences(entry.requirement(),module,exported,false);
            }
            for(var component:module.components()) if(component.kind().equals("USE_CASE")&&!access.containsKey(component.identity()))
                throw new IllegalArgumentException("Missing usecase authorization inventory: "+component.identity());
            for(var definition:module.exports()) {
                if(definition.kind().equals("PERMISSION")) validatePermission(definition);
                if(definition.kind().equals("USE_CASE")) {
                    var entry=access.get(definition.identity());
                    if(entry==null||!entry.inputs().equals(inputs(definition))||!entry.requirement().equals(definition.authorization()))
                        throw new IllegalArgumentException("Exported/runtime usecase authorization differs: "+definition.identity());
                    validateReferences(definition.authorization(),module,exported,true);
                }
            }
        }
    }
    private static void validatePermission(Definition definition) {
        if(definition.constants().isEmpty()||new HashSet<>(definition.constants()).size()!=definition.constants().size()
                ||definition.constants().stream().anyMatch(c->!VernacNames.isIdentifier(c))||!definition.fields().isEmpty()
                ||!definition.methods().isEmpty()||!definition.element().isEmpty())
            throw new IllegalArgumentException("Invalid permission declaration metadata: "+definition.identity());
    }
    private static void validateInputs(UseCaseAccess access) {
        var inputs=new HashMap<String,Field>();
        for(var field:access.inputs()) if(!VernacNames.isIdentifier(field.name())||field.mutable()||field.type()==null||!qualified(field.type().name())||inputs.putIfAbsent(field.name(),field)!=null)
            throw new IllegalArgumentException("Invalid usecase authorization input: "+access.identity());
        validateResources(access.requirement(),inputs,access.identity());
    }
    private static void validateResources(Authorization requirement,Map<String,Field> inputs,String identity) {
        if(!requirement.resource().isEmpty()) {
            var parameter=inputs.get(requirement.resource());
            if(parameter==null||parameter.type().optional()||!parameter.type().pageKind().equals("NONE")
                    ||!parameter.type().name().equals(requirement.resourceType()))
                throw new IllegalArgumentException("Invalid authorization resource binding: "+identity);
        }
        requirement.children().forEach(c->validateResources(c,inputs,identity));
    }
    private static void validateReferences(Authorization requirement,ModuleDescriptor owner,Map<String,Definition> exported,boolean publicContract) {
        if(requirement.mode().equals("PERMISSION")) {
            var declaration=exported.get(requirement.permission());
            if(declaration!=null) {
                if(!declaration.kind().equals("PERMISSION")||!declaration.constants().contains(requirement.member()))
                    throw new IllegalArgumentException("Invalid module permission reference: "+requirement.permission()+"."+requirement.member());
            } else if(publicContract||!owner.internalTypes().contains(requirement.permission()))
                throw new IllegalArgumentException("Missing exported permission: "+requirement.permission());
        }
        if(!requirement.resourceType().isEmpty()) {
            var resource=exported.get(requirement.resourceType());
            if(resource!=null && !Set.of("ID","VALUE_OBJECT","ENUM").contains(resource.kind())
                    || resource==null && (publicContract||!owner.internalTypes().contains(requirement.resourceType())))
                throw new IllegalArgumentException("Invalid/missing authorization resource type: "+requirement.resourceType());
        }
        requirement.children().forEach(c->validateReferences(c,owner,exported,publicContract));
    }
    static void checkExpected(Requirement expected,ModuleDescriptor actual,String consumer) {
        var identities=new HashSet<String>();
        for(var before:expected.usecaseAccess()) {
            if(!identities.add(before.identity()))throw new IllegalArgumentException("Duplicate expected usecase authorization: "+before.identity());
            validateInputs(before);
            var now=actual.usecaseAccess().stream().filter(a->a.identity().equals(before.identity())).findFirst();
            if(now.isEmpty()||!before.requirement().equals(now.get().requirement()))
                throw new IllegalArgumentException("Module "+consumer+" requires changed usecase authorization: "+before.identity()+". Built against "+expected.version()+"; resolved "+actual.coordinates()+". Review security requirements and rebuild the dependent module.");
        }
    }
    static String source(Authorization requirement) {
        return switch(requirement.mode()) {
            case "OMITTED" -> "";
            case "PUBLIC" -> "public";
            case "AUTHENTICATED" -> "authenticated";
            case "PERMISSION" -> "permission "+requirement.permission()+"."+requirement.member()+(requirement.resource().isEmpty()?"":" on "+requirement.resource());
            case "ALL","ANY" -> requirement.mode().toLowerCase(Locale.ROOT)+" { "+requirement.children().stream()
                    .map(c->source(c)+(c.mode().equals("PERMISSION")?";":"")).collect(java.util.stream.Collectors.joining(" "))+" }";
            default -> throw new IllegalArgumentException("Invalid authorization mode");
        };
    }
}
