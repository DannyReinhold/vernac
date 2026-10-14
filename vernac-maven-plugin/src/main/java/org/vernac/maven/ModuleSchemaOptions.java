// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.nio.file.*;
import java.util.List;
import org.vernac.compiler.application.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.SchemaBuilder;
import org.vernac.compiler.pipeline.ResolvedProject;

/** Module payload ownership and application delivery ownership are shared by every Maven goal. */
final class ModuleSchemaOptions {
    private ModuleSchemaOptions() { }
    static SchemaBuilder.Options read(Path base,ResolvedProject resolved,List<ModuleDescriptor> modules) throws java.io.IOException {
        boolean publishing=Files.isRegularFile(base.resolve(ModuleDefinition.FILE));
        Path file=base.resolve(ApplicationDefinition.FILE);
        boolean delivery=!publishing&&Files.isRegularFile(file)&&ApplicationBindings.requiresDelivery(
            ApplicationDefinition.parse(file,Files.readString(file)),resolved,modules);
        return new SchemaBuilder.Options(publishing,delivery);
    }
}
