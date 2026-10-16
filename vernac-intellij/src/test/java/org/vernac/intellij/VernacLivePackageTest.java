// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Java PSI in an isolated CoreApplicationEnvironment, not an installed completion popup. */
class VernacLivePackageTest {
  @TempDir Path temporary;

  @Test
  void nativePackageLosesLiveClassWithoutTheBridge() throws Exception {
    assertTrue(run(false).contains("NATIVE_PACKAGE_LEGACY_MISS_REPRODUCED"));
  }

  @Test
  void bridgeRetainsImportsFactoriesAndNativeMemberProcessing() throws Exception {
    assertTrue(run(true).contains("NATIVE_PACKAGE_FIXED_OK"));
  }

  @Test
  void launchesWithLongClasspathAndSpaces() throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var extended = new StringBuilder(classpath);
    for (int i = 0; extended.length() <= 40000; i++) {
      extended
          .append(java.io.File.pathSeparator)
          .append(temporary.resolve("unused classpath entry " + i));
    }
    assertTrue(run(true, extended.toString()).contains("NATIVE_PACKAGE_FIXED_OK"));
  }

  private String run(boolean fixed) throws Exception {
    return run(
        fixed,
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
  }

  private String run(boolean fixed, String classpath) throws Exception {
    Path directory =
        Files.createDirectories(temporary.resolve(fixed ? "fixed probe" : "legacy probe"));
    Path home = Files.createDirectories(directory.resolve("idea-home"));
    Files.createDirectories(home.resolve("bin"));
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    Path log = directory.resolve("probe.log");
    // Windows limits the process command line, including the entire SDK classpath.
    // Let the Java launcher read arguments from a file instead.
    Path arguments = directory.resolve("java.args");
    var options =
        List.of(
            // SDK local-file loading uses its NIO bridge, as in the IDE launcher.
            "--add-exports=java.base/sun.nio.fs=ALL-UNNAMED",
            "-Didea.home.path=" + home,
            "-Didea.config.path=" + directory.resolve("config"),
            "-Didea.system.path=" + directory.resolve("system"),
            "-cp",
            classpath,
            VernacNativePackageProbe.class.getName(),
            Boolean.toString(fixed),
            directory.toString());
    Files.writeString(
        arguments,
        options.stream()
            .map(VernacLivePackageTest::quoteArgument)
            .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "@" + arguments.toAbsolutePath())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Native PSI probe timed out");
      String output = Files.readString(log);
      assertEquals(0, process.exitValue(), output);
      return output;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static String quoteArgument(String value) {
    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }
}
