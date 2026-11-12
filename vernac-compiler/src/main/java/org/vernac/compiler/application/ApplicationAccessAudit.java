// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.application;

import java.util.*;
import javax.lang.model.element.Modifier;
import com.palantir.javapoet.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.modules.ModuleDescriptor;
import org.vernac.compiler.pipeline.ResolvedProject;

/** Generates application-owned inventory inspection, never invocation-time permission decisions. */
final class ApplicationAccessAudit {
    private ApplicationAccessAudit() {}
    static MethodSpec method(ResolvedProject project,List<ModuleDescriptor> modules) {
        var entries=CodeBlock.builder().add("$T.of(\n",List.class);int count=0;
        var audit=ClassName.get("org.vernac.runtime.authorization","UseCaseAccessAudit");
        for(var source:project.project().sources())if(!source.external())for(var top:source.unit().definitions())if(top instanceof UseCaseNode operation) {
            String identity=source.unit().namespace()+"."+operation.name();
            String mode=operation.authorization().map(a->a instanceof AuthorizationNode.Mode m?(m.authenticated()?"AUTHENTICATED":"PUBLIC"):a instanceof AuthorizationNode.Group g?(g.all()?"ALL":"ANY"):"PERMISSION").orElse("OMITTED");
            if(count++>0)entries.add(",\n");entries.add("new $T.Entry($S,$T.class,$S)",audit,identity,ClassName.get(source.unit().namespace()+".usecase",operation.name()),mode);
        }
        for(var module:modules)for(var access:module.usecaseAccess()) {
            var component=module.components().stream().filter(c->c.identity().equals(access.identity())&&c.kind().equals("USE_CASE")).findFirst().orElseThrow();
            if(count++>0)entries.add(",\n");entries.add("new $T.Entry($S,$T.class,$S)",audit,access.identity(),declared(component.javaName()),access.requirement().mode());
        }
        entries.add("\n)");
        return MethodSpec.methodBuilder("__vernacUseCaseAccessAudit").addModifiers(Modifier.PUBLIC,Modifier.STATIC)
                .addAnnotation(ClassName.get("org.springframework.context.annotation","Bean"))
                .returns(ClassName.get("org.springframework.beans.factory.config","BeanFactoryPostProcessor"))
                .addStatement("return factory -> $T.verify(factory,$L)",audit,entries.build()).build();
    }
    private static ClassName declared(String identity) {
        int dot=identity.lastIndexOf('.');return ClassName.get(identity.substring(0,dot),identity.substring(dot+1));
    }
}
