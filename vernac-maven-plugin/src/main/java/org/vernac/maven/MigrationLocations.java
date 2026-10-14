// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;
import java.io.File;
import java.nio.file.*;
import org.jspecify.annotations.Nullable;
import org.vernac.compiler.modules.ModuleDefinition;
/** Modules must not leak SQL into Flyway's application-default classpath location. */
final class MigrationLocations {
    private MigrationLocations() { }
    static File resolve(Path base,@Nullable File configured) {
        if(configured!=null)return configured;
        return base.resolve(Files.isRegularFile(base.resolve(ModuleDefinition.FILE))?"src/main/vernac-schema/migration":"src/main/resources/db/migration").toFile();
    }
}
