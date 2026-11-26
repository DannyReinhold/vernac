// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.pipeline.VernacProject;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.pipeline.VernacScenarioCompiler;
import org.vernac.compiler.symbols.ProjectSymbolIndex;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Generates test-scoped JUnit tests from Vernac scenarios. */
@Mojo(name = "test-compile", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES,
        requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public final class VernacTestCompileMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "vernac.sourceDirectory", defaultValue = "${project.basedir}/src/main/vernac")
    private File sourceDirectory;

    @Parameter(property = "vernac.testSourceDirectory", defaultValue = "${project.basedir}/src/test/vernac")
    private File testSourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/vernac")
    private File testOutputDirectory;

    @Parameter(property = "vernac.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "vernac.testProfile")
    private String profile;

    @Parameter(property = "vernac.testCases")
    private String testCases;

    @Parameter(property = "maven.test.skip", defaultValue = "false")
    private boolean skipTestCompilation;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip || skipTestCompilation) return;
        var compiler = new VernacScenarioCompiler();
        var root = testSourceDirectory.toPath();
        var output = testOutputDirectory.toPath();
        try {
            var sources = compiler.discover(root);
            var main = sources.isEmpty()
                    ? new ResolvedProject(new VernacProject(sourceDirectory.toPath(), List.of(),
                            new ProjectSymbolIndex(List.of())), Map.of(), Map.of(), List.of(), List.of())
                    : new VernacCompiler().analyzeProject(sourceDirectory.toPath(), ModuleClasspath.read(project));
            var selected = testCases == null ? null : Arrays.stream(testCases.split(",", -1)).map(String::strip)
                    .collect(Collectors.toUnmodifiableSet());
            int count = compiler.generateSelected(root, sources, main, output, profile, selected);
            project.addTestCompileSourceRoot(output.toString());
            getLog().info("Selected " + count + " Vernac scenario(s) for " + (profile == null ? "the default profiles" : profile) + ".");
        } catch (SemanticValidationException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not generate Vernac test sources", e);
        }
    }
}
