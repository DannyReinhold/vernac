// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;

/** Strict startup conversion, independent of global Spring converters. */
@NullMarked
public final class AdapterConfiguration {
  private AdapterConfiguration() {}

  /** Reads a required property. No failure retains the value, even through its cause. */
  public static <T> T read(
      Environment environment, String property, Class<T> type, String context) {
    try {
      String value = environment.getProperty(property);
      if (value == null || value.isBlank()) throw new IllegalArgumentException();
      Object result;
      if (type == String.class) result = value;
      else if (type == URI.class) result = URI.create(value);
      else if (type == Duration.class) result = Duration.parse(value);
      else if (type == Path.class) result = Path.of(value);
      else if (type == Integer.class) result = Integer.valueOf(value);
      else if (type == Long.class) result = Long.valueOf(value);
      else if (type == Boolean.class && (value.equals("true") || value.equals("false")))
        result = Boolean.valueOf(value);
      else throw new IllegalArgumentException();
      return type.cast(result);
    } catch (RuntimeException ignored) {
      throw new AdapterConfigurationException(
          context, property, "a nonblank " + type.getSimpleName());
    }
  }

  /** Applies the same nonblank/non-null contract to manually constructed adapters. */
  public static <T> T required(@Nullable T value, String context, String property) {
    if (value == null || value instanceof String text && text.isBlank())
      throw new AdapterConfigurationException(context, property, "a nonblank, non-null value");
    return value;
  }

  /** A REST base may contain a path, but no credentials, query or fragment. */
  public static URI httpBase(@Nullable URI value, String context, String property) {
    if (value == null
        || !("http".equalsIgnoreCase(value.getScheme())
            || "https".equalsIgnoreCase(value.getScheme()))
        || value.getHost() == null
        || value.getRawUserInfo() != null
        || value.getRawQuery() != null
        || value.getRawFragment() != null
        || value.getPort() > 65535)
      throw new AdapterConfigurationException(
          context,
          property,
          "an absolute HTTP(S) URI with a host and without credentials, query or fragment");
    return value;
  }
}
