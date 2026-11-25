// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.ScenarioRunSession.Outcome;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Executes generated Java 21 scenarios in the current JVM, with an isolated classloader for each build. */
public final class InProcessScenarioExecutor implements ScenarioRunSession.Executor {
    private final VernacCompiler vernac = new VernacCompiler();
    private final JavaCompiler javac;
    private final List<Path> classpath;

    public InProcessScenarioExecutor(List<Path> classpath) {
        this.classpath = classpath.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("Scenario execution requires a JDK");
    }

    @Override
    public synchronized Map<String, Outcome> execute(ScenarioRunSession.Execution request) throws IOException {
        var workspace = Files.createTempDirectory("vernac-scenarios-");
        try {
            var sources = Files.createDirectory(workspace.resolve("src"));
            var classes = Files.createDirectory(workspace.resolve("classes"));
            var files = new ArrayList<>(vernac.generate(request.project(), SchemaModel.empty(), Map.of()).generatedFiles());
            files.add(request.bridge());
            compile(files, sources, classes);
            var names = files.stream().map(InProcessScenarioExecutor::className).collect(Collectors.toUnmodifiableSet());
            var urls = new ArrayList<java.net.URL>();
            urls.add(classes.toUri().toURL());
            for (var dependency : classpath) urls.add(dependency.toUri().toURL());
            try (var loader = new GeneratedClassLoader(urls.toArray(java.net.URL[]::new), names)) {
                return run(loader, className(request.bridge()));
            }
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private void compile(List<JavaFile> files, Path sources, Path classes) throws IOException {
        var javaPaths = new ArrayList<Path>();
        for (var file : files) {
            file.writeTo(sources);
            javaPaths.add(sources.resolve(className(file).replace('.', '/') + ".java"));
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            String dependencies = classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
            var options = List.of("--release", "21", "-proc:none", "-classpath", dependencies, "-d", classes.toString());
            if (!Boolean.TRUE.equals(javac.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(javaPaths)).call()))
                throw new IOException("Could not compile generated scenario classes: " + diagnostics.getDiagnostics());
        }
    }

    private static Map<String, Outcome> run(ClassLoader loader, String bridge) throws IOException {
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            Class<?> tests = Class.forName(bridge, false, loader);
            var outcomes = new ConcurrentHashMap<String, Outcome>();
            var request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(tests))
                    .filters(EngineFilter.includeEngines("junit-jupiter")).build();
            LauncherFactory.create().execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier test, TestExecutionResult result) {
                    if (!test.isTest()) return;
                    outcomes.put(test.getDisplayName(), switch (result.getStatus()) {
                        case SUCCESSFUL -> Outcome.PASSED;
                        case FAILED -> Outcome.FAILED;
                        case ABORTED -> Outcome.SKIPPED;
                    });
                }

                @Override
                public void executionSkipped(TestIdentifier test, String reason) {
                    if (test.isTest()) outcomes.put(test.getDisplayName(), Outcome.SKIPPED);
                }
            });
            return Map.copyOf(outcomes);
        } catch (ClassNotFoundException e) {
            throw new IOException("Generated scenario bridge could not be loaded", e);
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static String className(JavaFile file) {
        return file.packageName() + "." + file.typeSpec().name();
    }

    private static final class GeneratedClassLoader extends URLClassLoader {
        private final Set<String> generated;

        private GeneratedClassLoader(java.net.URL[] urls, Set<String> generated) {
            super(urls, InProcessScenarioExecutor.class.getClassLoader());
            this.generated = generated;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!owns(name)) return super.loadClass(name, resolve);
            var loaded = findLoadedClass(name);
            if (loaded == null) loaded = findClass(name);
            if (resolve) resolveClass(loaded);
            return loaded;
        }

        private boolean owns(String name) {
            if (generated.contains(name)) return true;
            for (int nested = name.lastIndexOf('$'); nested >= 0; nested = name.lastIndexOf('$', nested - 1)) {
                if (generated.contains(name.substring(0, nested))) return true;
            }
            return false;
        }
    }
}
