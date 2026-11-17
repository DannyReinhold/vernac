// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import java.nio.file.*;
/** CI entrypoint: the exact Wizard implementation, with independent product choices. */
public final class SaasWizardAcceptanceProject {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        VernacProjectTemplate.copyTo(root,"Wizard acceptance","de.rsas","wizard-saas","de.rsas.wizard",VernacProjectTemplate.ProjectKind.SAAS_APPLICATION);
        try(var paths=Files.walk(root)) {
            for(var path:paths.filter(Files::isRegularFile).toList()) {
                String name=path.getFileName().toString();
                if(!(name.endsWith(".java")||name.endsWith(".vernac")||name.endsWith(".mjs")))continue;
                String text=Files.readString(path).replace("example.workspace_reader","product.auditor").replace("example.workspace_editor","product.author");
                if(name.equals("BillingSetup.java"))text=text.replace("offer(\"free\", 0, 3, 1000, 2)","offer(\"free\", 0, 3, 1000, 3)");
                Files.writeString(path,text);
            }
        }
    }
}
