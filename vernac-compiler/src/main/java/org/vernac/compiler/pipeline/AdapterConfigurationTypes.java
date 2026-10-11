package org.vernac.compiler.pipeline;

import java.util.Map;

/** Explicit technical scalar vocabulary shared by validation and generation. */
public final class AdapterConfigurationTypes {
  private AdapterConfigurationTypes() {}

  public static final Map<String, Class<?>> TYPES =
      Map.of(
          "String",
          String.class,
          "URI",
          java.net.URI.class,
          "Duration",
          java.time.Duration.class,
          "Path",
          java.nio.file.Path.class,
          "int",
          int.class,
          "long",
          long.class,
          "boolean",
          boolean.class);
}
