// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;
import java.util.List;
import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
public final class ListenerGenerator {
    public List<JavaFile> generate(ListenerNode listener, CompilationUnitNode unit, ResolvedProject project) {
        return new UseCaseGenerator().generate(listener.operation(), unit, project, true,listener.deferred());
    }
}
