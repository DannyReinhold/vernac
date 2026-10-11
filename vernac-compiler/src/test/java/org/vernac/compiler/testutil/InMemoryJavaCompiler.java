// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.testutil;

import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.pipeline.VernacCompilationResult;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.SecureClassLoader;
import java.util.*;

public class InMemoryJavaCompiler {

    public static record CompilationOutput(ClassLoader classLoader, List<Diagnostic<? extends JavaFileObject>> diagnostics, boolean success) {
        public Class<?> loadClass(String className) throws ClassNotFoundException {
            if (!success) {
                throw new IllegalStateException("Compilation failed: " + diagnostics);
            }
            return classLoader.loadClass(className);
        }
    }

    public static CompilationOutput compile(VernacCompilationResult result) {
        return compile(result.generatedFiles());
    }

    public static CompilationOutput compile(List<JavaFile> files) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (JavaFile file : files) {
            String className = (file.packageName().isEmpty() ? "" : file.packageName() + ".") + file.typeSpec().name();
            sources.put(className, file.toString());
        }
        return compile(sources);
    }

    /** Also accepts virtual Java documents assembled by editor tooling. */
    public static CompilationOutput compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("System Java Compiler not available. JDK required.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, byte[]> classBytes = new HashMap<>();

        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(diagnostics, null, null);
        JavaFileManager fileManager = new ForwardingJavaFileManager<JavaFileManager>(stdFileManager) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override
                            public void close() {
                                classBytes.put(className, toByteArray());
                            }
                        };
                    }
                };
            }
        };

        List<JavaFileObject> compilationUnits = new ArrayList<>();
        for (var source : sources.entrySet()) {
            String className = source.getKey();
            compilationUnits.add(new SimpleJavaFileObject(
                    URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE
            ) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source.getValue();
                }
            });
        }

        List<String> options = List.of(
                "--release", "21", "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path")
        );

        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
        boolean success = task.call();

        ClassLoader classLoader = new SecureClassLoader(InMemoryJavaCompiler.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classBytes.get(name);
                if (bytes != null) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        return new CompilationOutput(classLoader, diagnostics.getDiagnostics(), success);
    }
}
