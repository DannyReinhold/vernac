package org.vernac.runtime.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;

class AdapterConfigurationTest {
  private StandardEnvironment environment(String value) {
    var env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("test.adapter.value", value)));
    return env;
  }

  private <T> T read(String value, Class<T> type) {
    return AdapterConfiguration.read(
        environment(value), "test.adapter.value", type, "test.Adapter.value");
  }

  @Test
  void explicitScalarConversions() {
    assertEquals("  text  ", read("  text  ", String.class));
    assertEquals(URI.create("https://example.org/api"), read("https://example.org/api", URI.class));
    assertEquals(Duration.ofMillis(250), read("PT0.25S", Duration.class));
    assertEquals(Path.of("archive"), read("archive", Path.class));
    assertEquals(42, read("42", Integer.class));
    assertEquals(Long.MAX_VALUE, read(Long.toString(Long.MAX_VALUE), Long.class));
    assertEquals(false, read("false", Boolean.class));
  }

  @Test
  void missingInvalidAndPlaceholderFailuresNeverExposeValuesOrCauses() {
    for (var type :
        java.util.List.of(Integer.class, Long.class, Boolean.class, URI.class, Duration.class)) {
      var error =
          assertThrows(
              AdapterConfigurationException.class, () -> read("SECRET invalid value", type));
      assertTrue(error.getMessage().contains("test.adapter.value"));
      assertFalse(error.getMessage().contains("SECRET"));
      assertNull(error.getCause());
    }
    assertThrows(AdapterConfigurationException.class, () -> read("yes", Boolean.class));
    assertThrows(AdapterConfigurationException.class, () -> read("2147483648", Integer.class));
    var error =
        assertThrows(
            AdapterConfigurationException.class,
            () -> read("${SECRET_missing_property}", String.class));
    assertFalse(error.toString().contains("SECRET"));
    assertNull(error.getCause());
    assertThrows(AdapterConfigurationException.class, () -> read("  ", String.class));
    assertThrows(
        AdapterConfigurationException.class,
        () ->
            AdapterConfiguration.read(
                new StandardEnvironment(),
                "vernac.test.property.that.does.not.exist",
                String.class,
                "test.Adapter"));
  }

  @Test
  void restBasesRejectCredentialsAndInvalidEndpointsAtConstruction() {
    for (String value :
        java.util.List.of(
            "relative",
            "file:///archive",
            "https://user:SECRET@example.org",
            "https://example.org/?secret=SECRET",
            "https://example.org/#SECRET",
            "https://example.org:99999")) {
      var error =
          assertThrows(
              AdapterConfigurationException.class,
              () -> AdapterConfiguration.httpBase(URI.create(value), "Adapter", "base"));
      assertFalse(error.getMessage().contains("SECRET"));
      assertNull(error.getCause());
    }
    assertEquals(
        URI.create("https://example.org/api/"),
        AdapterConfiguration.httpBase(URI.create("https://example.org/api/"), "Adapter", "base"));
  }
}
