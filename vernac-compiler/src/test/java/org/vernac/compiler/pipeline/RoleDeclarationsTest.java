// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
class RoleDeclarationsTest {
    @TempDir Path root;
    private void source(String file,String text) throws Exception {
        Path path=root.resolve(file);Files.createDirectories(path.getParent());Files.writeString(path,text);
    }
    @Test void importedPermissionKeysProduceDeterministicApplicationOwnedCatalog() throws Exception {
        source("access/access.vernac","namespace access; permission Documents { VIEW; EDIT; }");
        source("app/a.vernac","namespace app; import access.Documents; role product.editor { permission Documents.EDIT; permission Documents.VIEW; }");
        source("app/b.vernac","namespace app; import access.Documents; role product.reader { permission Documents.VIEW; }");
        var resolved=new VernacCompiler().analyzeProject(root);
        var catalog=new RoleDeclarations().catalog(resolved);
        assertEquals(Map.of("format",1,"roles",List.of(
            Map.of("key","product.editor","permissions",List.of("access.Documents.EDIT","access.Documents.VIEW")),
            Map.of("key","product.reader","permissions",List.of("access.Documents.VIEW")))),catalog);
        assertEquals(1,new VernacCompiler().compileProject(root).generatedFiles().size());
        assertEquals(catalog,new RoleDeclarations().catalog(new VernacCompiler().analyzeProject(root)));
    }
    @Test void duplicateRolesAcrossFilesAndUnknownPermissionMembersHaveSourceLocations() throws Exception {
        source("app/a.vernac","namespace app; permission Rights { VIEW; } role app.reader { permission Rights.VIEW; }");
        source("app/b.vernac","namespace app;\nrole app.reader { permission Rights.UNKNOWN; }");
        var failure=assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root));
        assertTrue(failure.diagnostics().stream().anyMatch(d->d.message().contains("Duplicate role")&&d.location().line()==2));
        assertTrue(failure.diagnostics().stream().anyMatch(d->d.message().contains("Unknown permission member")&&d.location().line()==2));
    }
    @Test void unknownTypesDuplicateGrantsAndUsecaseResourceBindingsAreRejected() throws Exception {
        source("app/a.vernac","""
            namespace app;
            value Rights = VIEW;
            permission Access { VIEW; }
            role app.reader { permission Rights.VIEW; permission Missing.VIEW; permission Access.VIEW; permission Access.VIEW on id; }
            """);
        var failure=assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root));
        for(String message:List.of("not a domain type","Missing","Duplicate role permission","cannot bind"))
            assertTrue(failure.getMessage().contains(message),failure.getMessage());
    }
    @Test void emptyRolesAreExplicitAndRemovalDoesNotRetainStaleGrants() throws Exception {
        source("app/a.vernac","namespace app; role app.reader {}");
        var compiler=new VernacCompiler();
        assertEquals(List.of(Map.of("key","app.reader","permissions",List.of())),new RoleDeclarations().catalog(compiler.analyzeProject(root)).get("roles"));
        source("app/a.vernac","namespace app;");
        assertEquals(List.of(),new RoleDeclarations().catalog(compiler.analyzeProject(root)).get("roles"));
    }
}
