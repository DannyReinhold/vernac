// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.vernac.compiler.ast.*;

/** Versioned data, never executable source or Java serialization. */
public record ModuleDescriptor(int formatVersion,String contractVersion,String module,String groupId,
        String artifactId,String version,List<Definition> exports,List<Requirement> externalContracts,List<String> internalTypes,List<Component> components,boolean persistent,List<org.vernac.compiler.vocabulary.Vocabulary.Entry> vocabulary,List<UseCaseAccess> usecaseAccess) {
    public ModuleDescriptor(int formatVersion,String contractVersion,String module,String groupId,String artifactId,String version,
            List<Definition> exports,List<Requirement> externalContracts,List<String> internalTypes,List<Component> components,
            boolean persistent,List<org.vernac.compiler.vocabulary.Vocabulary.Entry> vocabulary) {
        this(formatVersion,contractVersion,module,groupId,artifactId,version,exports,externalContracts,internalTypes,components,persistent,vocabulary,
                ModuleAuthorization.defaultAccess(components,exports));
    }
    public ModuleDescriptor(int formatVersion,String contractVersion,String module,String groupId,String artifactId,String version,
            List<Definition> exports,List<Requirement> externalContracts,List<String> internalTypes,List<Component> components,boolean persistent) {
        this(formatVersion,contractVersion,module,groupId,artifactId,version,exports,externalContracts,internalTypes,components,persistent,List.of());
    }
    /** Retain the runtime/history obligation after the last current storage component is removed. */
    public ModuleDescriptor withPersistentHistory() {
        return new ModuleDescriptor(formatVersion,contractVersion,module,groupId,artifactId,version,exports,externalContracts,
                internalTypes,components,true,vocabulary,usecaseAccess);
    }
    public boolean activatable() {
        return persistent || components.stream().anyMatch(c->org.vernac.runtime.application.ActivationPlan.isFunctionalKind(c.kind()));
    }
    public static final String RESOURCE="META-INF/vernac/module.json";
    public static final ObjectMapper JSON=new ObjectMapper();
    public static final String CONTRACT="module-contracts-11";
    public record Type(String name,boolean optional,String pageKind) {
        public Type(String name,boolean optional) {this(name,optional,"NONE");}
        public Type {pageKind=pageKind==null?"NONE":pageKind;TypeNode.PageKind.valueOf(pageKind);}
    }
    public record Component(String identity,String kind,String javaName,List<String> dependencies) {
        public Component {dependencies=List.copyOf(dependencies);}
    }
    public record Field(String name,Type type,boolean mutable) {public Field(String name,Type type){this(name,type,false);}}
    public record Method(String name,Type returns,List<Field> parameters,String mode) {
        public Method(String name,Type returns,List<Field> parameters){this(name,returns,parameters,"DEFAULT");}
        public Method {parameters=List.copyOf(parameters);if(!Set.of("DEFAULT","READ","MODIFY","OFFSET_QUERY","COUNTED_OFFSET_QUERY","CURSOR_QUERY").contains(mode))throw new IllegalArgumentException("Invalid public method mode: "+mode);}
    }
    public record Definition(String identity,String kind,String javaName,List<Field> fields,List<String> constants,
            String element,String collectionKind,List<Method> methods,Map<String,String> javaMembers,
            String source,int line,int column,String fingerprint,String aggregateScope,int entityOwnershipVersion,Authorization authorization) {
        public Definition(String identity,String kind,String javaName,List<Field> fields,List<String> constants,
                String element,String collectionKind,List<Method> methods,Map<String,String> javaMembers,
                String source,int line,int column,String fingerprint,String aggregateScope,int entityOwnershipVersion) {
            this(identity,kind,javaName,fields,constants,element,collectionKind,methods,javaMembers,source,line,column,
                    fingerprint,aggregateScope,entityOwnershipVersion,Authorization.omitted());
        }
        public Definition(String identity,String kind,String javaName,List<Field> fields,List<String> constants,
                String element,String collectionKind,List<Method> methods,Map<String,String> javaMembers,
                String source,int line,int column,String fingerprint) {
            this(identity,kind,javaName,fields,constants,element,collectionKind,methods,javaMembers,
                    source,line,column,fingerprint,kind.equals("AGGREGATE")?"GLOBAL":"NONE",0);
        }
        public Definition {
            boolean validScope = kind.equals("AGGREGATE")
                    ? "GLOBAL".equals(aggregateScope) || "TENANT".equals(aggregateScope)
                    : "NONE".equals(aggregateScope);
            if (!validScope)
                throw new IllegalArgumentException("Invalid aggregate scope: " + identity);
            if (entityOwnershipVersion < 0 || entityOwnershipVersion > 1
                    || (!kind.equals("ENTITY") && entityOwnershipVersion != 0))
                throw new IllegalArgumentException("Invalid entity ownership contract: " + identity);
            Objects.requireNonNull(authorization, "Missing usecase authorization metadata; rebuild the module");
            if (!kind.equals("USE_CASE") && !authorization.mode().equals("OMITTED"))
                throw new IllegalArgumentException("Authorization metadata belongs only to usecases: " + identity);
            fields=List.copyOf(fields);constants=kind.equals("PERMISSION")?constants.stream().sorted().toList():List.copyOf(constants);
            methods=methods.stream().sorted(Comparator.comparing(m->m.name()+m.parameters())).toList();
            javaMembers=Collections.unmodifiableMap(new TreeMap<>(javaMembers));
        }
    }
    /** Ordered source-independent security tree. No policy implementation or input value is serialized. */
    public record Authorization(String mode,String permission,String member,String resource,String resourceType,List<Authorization> children) {
        public Authorization {
            Objects.requireNonNull(mode); Objects.requireNonNull(permission); Objects.requireNonNull(member);
            Objects.requireNonNull(resource); Objects.requireNonNull(resourceType); children=List.copyOf(children);
            ModuleAuthorization.validateShape(mode,permission,member,resource,resourceType,children);
        }
        public static Authorization omitted() { return new Authorization("OMITTED","","","","",List.of()); }
    }
    /** Includes internal operations for audit/review, without making their types importable. */
    public record UseCaseAccess(String identity,List<Field> inputs,Authorization requirement) {
        public UseCaseAccess { Objects.requireNonNull(identity); inputs=List.copyOf(inputs); Objects.requireNonNull(requirement); }
    }
    public record Requirement(String module,String groupId,String artifactId,String version,List<Definition> definitions,List<UseCaseAccess> usecaseAccess) {
        public Requirement(String module,String groupId,String artifactId,String version,List<Definition> definitions) {
            this(module,groupId,artifactId,version,definitions,ModuleAuthorization.defaultExportAccess(definitions));
        }
        public Requirement { definitions=List.copyOf(definitions); usecaseAccess=List.copyOf(usecaseAccess); }
    }
    public static void write(java.nio.file.Path target,Object value) throws java.io.IOException {
        java.nio.file.Files.createDirectories(target.getParent());
        var temporary=java.nio.file.Files.createTempFile(target.getParent(),"vernac-module-",".tmp");
        try {
            java.nio.file.Files.writeString(temporary,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            try {java.nio.file.Files.move(temporary,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException e) {java.nio.file.Files.move(temporary,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        } finally {java.nio.file.Files.deleteIfExists(temporary);}
    }
    public String coordinates() { return groupId+":"+artifactId+":"+version; }
    public ModuleDescriptor(int formatVersion,String contractVersion,String module,String groupId,String artifactId,String version,List<Definition> exports,List<Requirement> externalContracts) {
        this(formatVersion,contractVersion,module,groupId,artifactId,version,exports,externalContracts,List.of(),List.of(),false);
    }
    public ModuleDescriptor(int formatVersion,String contractVersion,String module,String groupId,String artifactId,String version,List<Definition> exports,List<Requirement> externalContracts,List<String> internalTypes) {
        this(formatVersion,contractVersion,module,groupId,artifactId,version,exports,externalContracts,internalTypes,List.of(),false);
    }
    public ModuleDescriptor(int formatVersion,String contractVersion,String module,String groupId,String artifactId,String version,List<Definition> exports,List<Requirement> externalContracts,List<String> internalTypes,List<Component> components) {
        this(formatVersion,contractVersion,module,groupId,artifactId,version,exports,externalContracts,internalTypes,components,false);
    }
    public ModuleDescriptor {
        if(formatVersion!=1 || !CONTRACT.equals(contractVersion))throw new IllegalArgumentException("Unsupported Vernac module format/contract version; expected " + CONTRACT + ". Rebuild producer and consumer artifacts.");
        usecaseAccess=Objects.requireNonNull(usecaseAccess,"Missing module authorization inventory; rebuild the producer").stream().sorted(Comparator.comparing(UseCaseAccess::identity)).toList();
        vocabulary=vocabulary==null?List.of():List.copyOf(vocabulary);
        components=components==null?List.of():components.stream().sorted(Comparator.comparing(Component::identity)).toList();
        exports=List.copyOf(exports);externalContracts=List.copyOf(externalContracts);internalTypes=internalTypes==null?List.of():List.copyOf(internalTypes);
        Objects.requireNonNull(module);Objects.requireNonNull(groupId);Objects.requireNonNull(artifactId);Objects.requireNonNull(version);
    }
}
