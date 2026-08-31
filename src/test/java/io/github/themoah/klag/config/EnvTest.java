package io.github.themoah.klag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Unit tests for {@link Env}.
 *
 * <p>Env vars can't be set in-process, so these exercise the system-property fallbacks
 * ({@code -DNAME} and dotted {@code -Dname.dotted}) and precedence. The env-var path stays
 * highest in {@link Env#resolve} and is unchanged from before.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class EnvTest {

  private static final String NAME = "KLAG_TEST_PORT";
  private static final String DOTTED = "klag.test.port";

  private String originalExact;
  private String originalDotted;

  @BeforeEach
  void saveAndClearProps() {
    originalExact = System.getProperty(NAME);
    originalDotted = System.getProperty(DOTTED);
    System.clearProperty(NAME);
    System.clearProperty(DOTTED);
  }

  @AfterEach
  void restoreProps() {
    restoreProperty(NAME, originalExact);
    restoreProperty(DOTTED, originalDotted);
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  @Test
  void exactPropertyResolves() {
    System.setProperty(NAME, "8881");
    assertEquals(8881, Env.getInt(NAME, 8888));
  }

  @Test
  void dottedPropertyResolves() {
    System.setProperty(DOTTED, "8881");
    assertEquals(8881, Env.getInt(NAME, 8888));
  }

  @Test
  void exactPropertyWinsOverDotted() {
    System.setProperty(NAME, "1111");
    System.setProperty(DOTTED, "2222");
    assertEquals(1111, Env.getInt(NAME, 8888));
  }

  @Test
  void stringFromExactProperty() {
    System.setProperty(NAME, "prometheus");
    assertEquals("prometheus", Env.getString(NAME, "none"));
  }

  @Test
  void stringFromDottedProperty() {
    System.setProperty(DOTTED, "prometheus");
    assertEquals("prometheus", Env.getString(NAME, "none"));
  }

  @Test
  void exactStringPropertyWinsOverDotted() {
    System.setProperty(NAME, "datadog");
    System.setProperty(DOTTED, "otlp");
    assertEquals("datadog", Env.getString(NAME, "none"));
  }

  @Test
  void missingStringUsesDefault() {
    assertEquals("none", Env.getString(NAME, "none"));
  }

  @Test
  void blankStringUsesDefault() {
    System.setProperty(DOTTED, "   ");
    assertEquals("none", Env.getString(NAME, "none"));
  }

  @Test
  void blankExactStringFallsBackToDotted() {
    System.setProperty(NAME, "   ");
    System.setProperty(DOTTED, "prometheus");
    assertEquals("prometheus", Env.getString(NAME, "none"));
  }

  @Test
  void absentUsesDefault() {
    assertEquals(8888, Env.getInt(NAME, 8888));
  }

  @Test
  void invalidUsesDefault() {
    System.setProperty(NAME, "not-a-number");
    assertEquals(8888, Env.getInt(NAME, 8888));
  }

  @Test
  void blankUsesDefault() {
    System.setProperty(NAME, "   ");
    assertEquals(8888, Env.getInt(NAME, 8888));
  }

  @Test
  void boolFromProperty() {
    System.setProperty(DOTTED, "true");
    assertTrue(Env.getBool(NAME, false));
  }

  @Test
  void invalidBoolUsesDefault() {
    System.setProperty(NAME, "not-a-boolean");
    assertTrue(Env.getBool(NAME, true));
  }
}
