// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.pipeline.VernacCompiler;

class IdentityModuleContractIT {
    @TempDir Path root;
    @Test void actualProducerJarExportsOnlyNominalContractsWithoutActivationOrRegeneration() throws Exception {
        Path jar=Path.of(System.getProperty("identity.jar"));assertTrue(Files.isRegularFile(jar));
        var module=ModuleArtifacts.read(List.of(jar)).getFirst();ModuleContracts.checkDependencies(List.of(module));
        assertEquals("vernac.saas.identity",module.module());assertFalse(module.activatable());assertFalse(module.persistent());
        assertTrue(module.components().isEmpty());assertTrue(module.usecaseAccess().isEmpty());
        assertEquals(Set.of("org.vernac.saas.identity.UserId","org.vernac.saas.identity.IdentityStatus","org.vernac.saas.identity.IdentityDirectory"),module.exports().stream().map(ModuleDescriptor.Definition::identity).collect(java.util.stream.Collectors.toSet()));
        assertTrue(module.vocabulary().stream().anyMatch(t->t.name().equals("UserId")&&t.description().contains("stable human")));
        Path sources=root.resolve("consumer"),file=sources.resolve("consumer/model.vernac");Files.createDirectories(file.getParent());
        String document=Files.readString(Path.of("../../docs/contracts/saas-identity-foundation.md"));
        var example=java.util.regex.Pattern.compile("```vernac\\n(.*?)```",java.util.regex.Pattern.DOTALL).matcher(document);
        assertTrue(example.find());Files.writeString(file,example.group(1));
        var compiler=new VernacCompiler();var consumer=compiler.analyzeProject(sources,List.of(module));
        var generated=compiler.generate(consumer,org.vernac.compiler.persistence.SchemaModel.empty(),Map.of());
        assertTrue(generated.generatedFiles().stream().noneMatch(f->f.packageName().startsWith("org.vernac.saas")));
        Path javaRoot=root.resolve("java"),compiled=Files.createDirectories(root.resolve("compiled"));generated.writeTo(javaRoot);
        var arguments=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",jar+java.io.File.pathSeparator+System.getProperty("java.class.path"),"-d",compiled.toString()));
        try(var paths=Files.walk(javaRoot)){paths.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        try(var archive=new JarFile(jar.toFile())) {
            assertNotNull(archive.getJarEntry(ModuleDescriptor.RESOURCE));
            assertNull(archive.getJarEntry("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
            assertFalse(archive.stream().anyMatch(e->e.getName().contains("/security/")||e.getName().startsWith("db/")));
        }
    }
}
