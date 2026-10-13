// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.vernac.compiler.ast.*;

/** Versioned data, never executable source or Java serialization. */
public record ModuleDescriptor(int formatVersion,String contractVersion,String module,String groupId,
        String artifactId,String version,List<Definition> exports,List<Requirement> externalContracts,List<String> internalTypes) {
    public static final String RESOURCE="META-INF/vernac/module.json";
    public static final ObjectMapper JSON=new ObjectMapper();
    public static final String CONTRACT="type-modules-1";
    public record Type(String name,boolean optional) { }
    public record Field(String name,Type type) { }
    public record Method(String name,Type returns,List<Field> parameters) { public Method {parameters=List.copyOf(parameters);} }
    public record Definition(String identity,String kind,String javaName,List<Field> fields,List<String> constants,
            String element,String collectionKind,List<Method> methods,Map<String,String> javaMembers,
            String source,int line,int column,String fingerprint) {
        public Definition {
            fields=List.copyOf(fields);constants=List.copyOf(constants);
            methods=methods.stream().sorted(Comparator.comparing(m->m.name()+m.parameters())).toList();
            javaMembers=Collections.unmodifiableMap(new TreeMap<>(javaMembers));
        }
    }
    public record Requirement(String module,String groupId,String artifactId,String version,List<Definition> definitions) { public Requirement {definitions=List.copyOf(definitions);} }
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
        this(formatVersion,contractVersion,module,groupId,artifactId,version,exports,externalContracts,List.of());
    }
    public ModuleDescriptor {
        exports=List.copyOf(exports);externalContracts=List.copyOf(externalContracts);internalTypes=internalTypes==null?List.of():List.copyOf(internalTypes);
        if(formatVersion!=1 || !CONTRACT.equals(contractVersion))throw new IllegalArgumentException("Unsupported Vernac module format/contract version");
        Objects.requireNonNull(module);Objects.requireNonNull(groupId);Objects.requireNonNull(artifactId);Objects.requireNonNull(version);
    }
}
