// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.persistence.SchemaModel;

class AuthorizationModuleTest {
    @TempDir Path root;
    private static final String MODEL="""
        namespace library.core;
        id InvoiceId;
        permission Rights { VIEW; EDIT; }
        usecase Submit(InvoiceId) returns InvoiceId
            requires permission Rights.VIEW on invoiceId
        behavior {execute {return invoiceId;}}
        """;
    private static final String EXPORTS="export library.core.InvoiceId; export library.core.Rights; export library.core.Submit;";
    private ModuleDescriptor publish(String folder,String model,String exports,String version,List<ModuleDescriptor> dependencies) throws Exception {
        String ns=model.substring(model.indexOf("namespace ")+10,model.indexOf(';')).trim();
        Path source=root.resolve(folder+"/src/main/vernac"); Path file=source.resolve(ns.replace('.','/')+"/model.vernac");
        Files.createDirectories(file.getParent()); Files.writeString(file,model);
        var compiler=new VernacCompiler(); var resolved=compiler.analyzeProject(source,dependencies);
        var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        return ModuleContracts.publish(ModuleDefinition.parse(root.resolve(folder+"/vernac-module.vmodule"),"module example."+folder+" {"+exports+"}"),
                resolved,generated.generatedFiles(),"example",folder,version,dependencies);
    }
    private ModuleDescriptor provider(String model,String version) throws Exception { return publish("provider",model,EXPORTS,version,List.of()); }
    private ModuleDescriptor.Definition definition(ModuleDescriptor module,String kind) { return module.exports().stream().filter(d->d.kind().equals(kind)).findFirst().orElseThrow(); }
    @Test void exportsRequireExplicitPermissionClosureAndRoundTripTheOrderedTree() throws Exception {
        var error=assertThrows(SemanticValidationException.class,()->publish("missing",MODEL,"export library.core.InvoiceId; export library.core.Submit;","1",List.of()));
        assertTrue(error.getMessage().contains("explicit export library.core.Rights"));
        var module=provider(MODEL.replace("requires permission Rights.VIEW on invoiceId", "requires all {permission Rights.VIEW on invoiceId; any {permission Rights.EDIT on invoiceId; permission Rights.VIEW;}}"),"1");
        ModuleContracts.checkDependencies(List.of(module));
        assertEquals("library.core.authorization.Rights",definition(module,"PERMISSION").javaName());
        var requirement=definition(module,"USE_CASE").authorization();
        assertEquals("ALL",requirement.mode()); assertEquals("ANY",requirement.children().get(1).mode());
        assertEquals("library.core.InvoiceId",requirement.children().getFirst().resourceType());
        var decoded=ModuleDescriptor.JSON.readValue(ModuleDescriptor.JSON.writeValueAsString(module),ModuleDescriptor.class);
        assertEquals(module,decoded); assertEquals(requirement,decoded.usecaseAccess().getFirst().requirement());
    }
    @Test void importsRestoreGuardsAndPermissionTypesWithoutRegeneratingProducerCode() throws Exception {
        var module=provider(MODEL,"1");
        Path source=root.resolve("consumer/src/main/vernac/app/model.vernac"); Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace app; import library.core.InvoiceId; import library.core.Rights; usecase Read(InvoiceId) requires permission Rights.VIEW on invoiceId behavior {execute {}}");
        var compiler=new VernacCompiler(); var resolved=compiler.analyzeProject(root.resolve("consumer/src/main/vernac"),List.of(module));
        var producer=resolved.project().sources().stream().filter(VernacSourceFile::external).findFirst().orElseThrow();
        var operation=producer.unit().useCases().getFirst();
        assertTrue(operation.authorization().orElseThrow() instanceof AuthorizationNode.Permission);
        var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        assertTrue(generated.generatedFiles().stream().noneMatch(f->f.packageName().startsWith("library.core")));
        assertTrue(generated.generatedFiles().toString().contains("library.core.authorization.Rights"));
        String view=ModuleArtifacts.view(module,"library.core");
        assertTrue(view.contains("permission Rights")); assertTrue(view.contains("requires permission library.core.Rights.VIEW on invoiceId"));
        assertDoesNotThrow(()->new VernacSourceParser().parse("dependency-view",view));
        assertTrue(ModuleArtifacts.manifestView(module).contains("Access library.core.Submit"));
        Path attachment=root.resolve("sources.jar");
        try(var jar=new java.util.jar.JarOutputStream(Files.newOutputStream(attachment))) {
            jar.putNextEntry(new java.util.jar.JarEntry(definition(module,"PERMISSION").source()));
            jar.write(MODEL.getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
        }
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/vernac-module-sources.json"),ModuleDescriptor.JSON.writeValueAsString(Map.of(module.module(),attachment.toString())));
        var location=ModuleSources.locations(root,List.of(module)).get("library.core.Rights");
        assertNotNull(location); assertEquals("Rights",location.text().substring(location.start(),location.end()));
        assertTrue(location.path().toString().contains("attached"));
    }
    @Test void additiveKeysAndMemberReorderingAreCompatibleButRemovalNeedsGrantReview() throws Exception {
        var before=provider(MODEL,"1"); var reordered=provider(MODEL.replace("VIEW; EDIT;","EDIT; VIEW;"),"1.1");
        assertEquals(definition(before,"PERMISSION").fingerprint(),definition(reordered,"PERMISSION").fingerprint());
        var added=provider(MODEL.replace("VIEW; EDIT;","VIEW; EDIT; EXPORT;"),"1.2");
        assertTrue(ModuleContracts.difference(definition(before,"PERMISSION"),definition(added,"PERMISSION")).isEmpty());
        var removed=provider(MODEL.replace("VIEW; EDIT;","VIEW;"),"2");
        assertTrue(ModuleContracts.difference(definition(before,"PERMISSION"),definition(removed,"PERMISSION")).orElseThrow().contains("stored grants"));
    }
    @Test void publicRequirementsIgnoreBodyPrivateHelpersAndAliasNoiseButNotSecurityChanges() throws Exception {
        var before=provider(MODEL,"1");
        var noise=provider(MODEL.replace("namespace library.core;","namespace library.core; // comment\n")
                .replace("Rights.VIEW on invoiceId", "library.core.Rights.VIEW on invoiceId")
                .replace("execute {return invoiceId;}","execute {return helper(invoiceId);} private InvoiceId helper(InvoiceId input) {return input;}"),"1.1");
        assertEquals(definition(before,"USE_CASE").fingerprint(),definition(noise,"USE_CASE").fingerprint());
        var changed=provider(MODEL.replace("requires permission Rights.VIEW on invoiceId","requires authenticated"),"2");
        assertTrue(ModuleContracts.difference(definition(before,"USE_CASE"),definition(changed,"USE_CASE")).orElseThrow().contains("authorization"));
        var rebound=provider(MODEL.replace("(InvoiceId)","(InvoiceId target)").replace("on invoiceId","on target").replace("return invoiceId;","return target;"),"2.1");
        assertTrue(ModuleContracts.difference(definition(before,"USE_CASE"),definition(rebound,"USE_CASE")).orElseThrow().contains("authorization"));
        var first=provider(MODEL.replace("requires permission Rights.VIEW on invoiceId","requires all {permission Rights.VIEW on invoiceId;permission Rights.EDIT on invoiceId;}"),"3");
        var reverse=provider(MODEL.replace("requires permission Rights.VIEW on invoiceId","requires all {permission Rights.EDIT on invoiceId;permission Rights.VIEW on invoiceId;}"),"4");
        assertTrue(ModuleContracts.difference(definition(first,"USE_CASE"),definition(reverse,"USE_CASE")).isPresent());
    }
    @Test void functionalDependencySnapshotsDetectInternalRequirementChangesButKeyOnlyImportsDoNotActivate() throws Exception {
        String model="""
            namespace library.core;
            id InvoiceId;
            permission SecretRights { VIEW; EDIT; }
            usecase PrivateStep(InvoiceId) requires permission SecretRights.VIEW on invoiceId behavior {execute {}}
            usecase Facade(InvoiceId) returns InvoiceId requires public behavior {execute {return invoiceId;}}
            """;
        var before=publish("producer",model,"export library.core.InvoiceId;export library.core.Facade;","1",List.of());
        var consumer=publish("consumer","namespace app; import library.core.InvoiceId; import library.core.Facade; usecase Relay(InvoiceId) returns InvoiceId uses Facade behavior {execute {return self.facade().execute(invoiceId);}}",
                "export app.Relay;","1",List.of(before));
        assertEquals(2,consumer.externalContracts().getFirst().usecaseAccess().size());
        var after=publish("producer",model.replace("SecretRights.VIEW on", "SecretRights.EDIT on"),"export library.core.InvoiceId;export library.core.Facade;","2",List.of());
        assertTrue(ModuleContracts.difference(definition(before,"USE_CASE"),definition(after,"USE_CASE")).isEmpty());
        var error=assertThrows(IllegalArgumentException.class,()->ModuleContracts.checkDependencies(List.of(consumer,after)));
        assertTrue(error.getMessage().contains("PrivateStep")); assertTrue(error.getMessage().contains("Built against 1"));
        assertTrue(error.getMessage().contains("example.consumer"));
        var keys=publish("keys","namespace library.keys; permission Rights {VIEW;}","export library.keys.Rights;","1",List.of());
        assertFalse(keys.activatable()); assertTrue(keys.components().isEmpty());
    }
    @Test void transitivePublicRequirementClosureIsPreservedWithoutForeignReexports() throws Exception {
        var provider=provider(MODEL,"1");
        var relay=publish("relay","namespace relay; import library.core.InvoiceId; import library.core.Rights; usecase Submit(InvoiceId) requires permission Rights.VIEW on invoiceId behavior {execute {}}",
                "export relay.Submit;","1",List.of(provider));
        assertTrue(relay.externalContracts().stream().flatMap(r->r.definitions().stream()).anyMatch(d->d.kind().equals("PERMISSION")));
        ModuleContracts.checkDependencies(List.of(provider,relay));
        var changed=provider(MODEL.replace("requires permission Rights.VIEW on invoiceId","requires public"),"2");
        // Relay uses only the dictionary/ID contract, not the producer's usecase. Its security tree is unchanged.
        assertDoesNotThrow(()->ModuleContracts.checkDependencies(List.of(changed,relay)));
    }
    @Test void malformedOrIncompleteSecurityMetadataAndOldContractAreRejected() throws Exception {
        var module=provider(MODEL,"1");
        ObjectNode json=ModuleDescriptor.JSON.valueToTree(module);
        ((ObjectNode)((ArrayNode)json.get("exports")).get(0)).remove("authorization");
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->ModuleDescriptor.JSON.treeToValue(json,ModuleDescriptor.class));
        ObjectNode old=ModuleDescriptor.JSON.valueToTree(module); old.put("contractVersion","module-contracts-10");
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->ModuleDescriptor.JSON.treeToValue(old,ModuleDescriptor.class));
        ObjectNode missing=ModuleDescriptor.JSON.valueToTree(module); missing.set("usecaseAccess",ModuleDescriptor.JSON.createArrayNode());
        var incomplete=ModuleDescriptor.JSON.treeToValue(missing,ModuleDescriptor.class);
        assertThrows(IllegalArgumentException.class,()->ModuleContracts.checkDependencies(List.of(incomplete)));
        assertThrows(IllegalArgumentException.class,()->new ModuleDescriptor.Authorization("ANY","","","","",List.of()));
        assertThrows(IllegalArgumentException.class,()->new ModuleDescriptor.Authorization("PUBLIC","library.Rights","VIEW","","",List.of()));
    }
}
