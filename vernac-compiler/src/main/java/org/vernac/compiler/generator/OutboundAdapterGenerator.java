// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;
import java.util.List;
import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
/** Generates one complete port implementation. */
public final class OutboundAdapterGenerator {
    public List<JavaFile> generate(OutboundAdapterNode adapter, CompilationUnitNode unit, ResolvedProject project) {
        var files = new java.util.ArrayList<>(new BeanBehaviorGenerator().generateAdapter(adapter,unit,project));
        if (!adapter.schemas().isEmpty()) files.add(new DtoGenerator().generate(adapter,unit,project.schemas().get(adapter)));
        if (!adapter.mappings().isEmpty()) files.addAll(new MappingGenerator().generate(adapter,unit,project));
        return List.copyOf(files);
    }
}
