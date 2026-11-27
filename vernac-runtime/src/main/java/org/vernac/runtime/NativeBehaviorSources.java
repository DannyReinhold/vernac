// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Versioned source positions kept alongside native behavior classes. */
public final class NativeBehaviorSources {
    public static final String PREFIX = "META-INF/vernac/native-sources/";
    private static final int VERSION = 1;

    private NativeBehaviorSources() { }

    public record Position(String instruction, int line, int column) { }

    public static String resource(String implementationClass, int program) {
        return PREFIX + implementationClass.replace('.', '/') + "/" + program + ".bin";
    }

    public static void write(OutputStream output, String declaration, String source, List<Position> positions)
            throws IOException {
        var data = new DataOutputStream(output);
        data.writeInt(VERSION);
        data.writeUTF(declaration);
        data.writeUTF(source);
        data.writeInt(positions.size());
        for (var at : positions) {
            data.writeUTF(at.instruction());
            data.writeInt(at.line());
            data.writeInt(at.column());
        }
        data.flush();
    }

    static void attach(RuntimeException failure, Class<?> implementation, int program, String instruction) {
        if (implementation == null) return;
        try (var input = DevelopmentRevisions.sourceMap(implementation, program)) {
            if (input == null) return;
            var data = new DataInputStream(input);
            if (data.readInt() != VERSION) return;
            String declaration = data.readUTF();
            String source = data.readUTF();
            int count = data.readInt();
            for (int index = 0; index < count; index++) {
                String path = data.readUTF();
                int line = data.readInt();
                int column = data.readInt();
                if (path.equals(instruction)) {
                    failure.addSuppressed(new SourceTrace(declaration, source, line, column));
                    return;
                }
            }
        } catch (IOException ignored) {
            // Missing or invalid debug metadata must preserve the original behavior failure.
        }
    }

    /** Vernac context carried as a suppressed detail on the original runtime error. */
    public static final class SourceTrace extends RuntimeException {
        private final String declaration;
        private final String source;
        private final int line;
        private final int column;

        private SourceTrace(String declaration, String source, int line, int column) {
            super(declaration + " at " + source + ":" + line + ":" + column, null, false, false);
            this.declaration = declaration;
            this.source = source;
            this.line = line;
            this.column = column;
        }

        public String declaration() { return declaration; }
        public String source() { return source; }
        public int line() { return line; }
        public int column() { return column; }
    }
}
