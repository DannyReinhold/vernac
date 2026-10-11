// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;
import java.util.List;
import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
/** Domain service entry point into shared bean behavior generation. */
public final class DomainServiceGenerator {
    public List<JavaFile> generate(DomainServiceNode service, CompilationUnitNode unit, ResolvedProject project) {
        return new BeanBehaviorGenerator().generate(service,unit,project);
    }
}
