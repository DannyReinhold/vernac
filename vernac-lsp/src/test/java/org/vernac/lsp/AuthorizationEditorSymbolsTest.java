// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;

class AuthorizationEditorSymbolsTest {
    private final Path root=Path.of("auth-editor").toAbsolutePath();
    private final Path keys=root.resolve("library/keys.vernac"),app=root.resolve("app/model.vernac");
    private final String library="namespace library; term Rights {description \"Invoice capabilities.\";} permission Rights {VIEW; EXPORT; String;} value Status = VIEW | EDIT;";
    private final String header="namespace app; import library.Rights; id InvoiceId; value Label(String) list Labels; ";
    private VernacProjectSymbols symbols(String model) {return new VernacProjectSymbols(root,Map.of(keys,library,app,model));}
    private Position at(String model,String marker) {return new Position(0,model.lastIndexOf(marker)+marker.length());}
    private Set<String> choices(String model,String marker) {
        var items=symbols(model).complete(app,at(model,marker));assertNotNull(items);
        return items.stream().map(CompletionItem::getLabel).collect(java.util.stream.Collectors.toSet());
    }
    @Test void incompleteKeysCompleteVisibleNominalPermissionsInsideNestedGroups() {
        String model=header+"usecase Read(InvoiceId) requires all {any {permission Rights.VI;}} behavior {execute {}}";
        assertEquals(Set.of("Rights.VIEW"),choices(model,"Rights.VI"));
        assertTrue(choices(model.replace("Rights.VI","Ri"),"permission Ri").contains("Rights.EXPORT"));
        assertEquals(Set.of("Rights.EXPORT","Rights.String","Rights.VIEW"),choices(model.replace("Rights.VI","Rights."),"Rights."));
        assertTrue(choices(model.replace("Rights.VI","library.Ri"),"library.Ri").contains("library.Rights.VIEW"));
        String qualified=model.replace("Rights.VI","library.Rights.EX");
        assertEquals(Set.of("library.Rights.EXPORT"),choices(qualified,"library.Rights.EX"));
    }
    @Test void resourcesIncludeOnlyRequiredNominalInputsWithImplicitAndExplicitNames() {
        String model=header+"port Store {void save(InvoiceId);}"+"usecase Read(InvoiceId, Label title, Label? optional, Labels labels) requires permission Rights.VIEW on tit uses Store store behavior {execute {}}";
        assertEquals(Set.of("title"),choices(model,"on tit"));
        var all=choices(model.replace("on tit","on "),"on ");
        assertEquals(Set.of("invoiceId","title"),all);
    }
    @Test void navigatesTypeMemberAndResourceAndExplainsTheirDifferentMeaning() {
        String model=header+"usecase Read(InvoiceId invoice) requires permission Rights.VIEW on invoice behavior {execute {}}";
        var symbols=symbols(model);
        var type=symbols.definition(app,at(model,"permission Rig")).getFirst();
        assertEquals(library.indexOf("permission Rights")+11,type.getRange().getStart().getCharacter());
        var member=symbols.definition(app,at(model,"Rights.VI")).getFirst();
        assertEquals(keys.toUri().toString(),member.getUri());assertEquals(library.indexOf("{VIEW")+1,member.getRange().getStart().getCharacter());
        var resource=symbols.definition(app,at(model,"on invo")).getFirst();
        assertEquals(model.indexOf("InvoiceId invoice")+10,resource.getRange().getStart().getCharacter());
        assertTrue(symbols.hover(app,at(model,"Rights.VI")).contains("Invoice capabilities."));
        assertTrue(symbols.hover(app,at(model,"on invo")).contains("not an ownership grant"));
    }
    @Test void missingAmbiguousOrWrongKindKeysDoNotAcquireGuessedTargets() {
        String model=header.replace("import library.Rights;", "")+"usecase Read() requires permission Rights.VI behavior {execute {}}";
        assertTrue(choices(model,"Rights.VI").isEmpty());
        String wrong=header.replace("import library.Rights;","import library.Status;")+"usecase Read() requires permission Status.VI behavior {execute {}}";
        assertTrue(choices(wrong,"Status.VI").isEmpty());
        var maps=Map.of(keys,library,root.resolve("other/keys.vernac"),"namespace other; permission Rights {VIEW;}",app,
            model.replace("namespace app;","namespace app; import library.*; import other.*;"));
        var ambiguous=new VernacProjectSymbols(root,maps);
        assertTrue(ambiguous.complete(app,at(maps.get(app),"Rights.VI")).isEmpty());
    }
    @Test void liveEditsRefreshKeysAndInvalidResourcesHaveNoNavigation() {
        String model=header+"usecase Read(InvoiceId? invoiceId) requires permission Rights.VIEW on invoiceId behavior {execute {}}";
        assertTrue(symbols(model).definition(app,at(model,"on invo")).isEmpty());
        String edited=library.replace("VIEW; EXPORT; String;","NEW;");
        var symbols=new VernacProjectSymbols(root,Map.of(keys,edited,app,model.replace("Rights.VIEW","Rights.N")));
        assertTrue(symbols.complete(app,at(model.replace("Rights.VIEW","Rights.N"),"Rights.N")).stream().anyMatch(i->i.getLabel().equals("Rights.NEW")));
    }
}
