package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.Appender;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link MetricsConfig}.
 *
 * <p>System-property cases run in-process with their original values restored after
 * each test. Environment precedence uses a child JVM because Java cannot portably
 * mutate the current process environment.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MetricsConfigTest {

  private static final List<String> METRICS_ENV_NAMES = List.of(
    "METRICS_REPORTER",
    "METRICS_INTERVAL_MS",
    "METRICS_GROUP_FILTER",
    "METRICS_GROUP_EXCLUDE",
    "METRICS_JVM_ENABLED",
    "LAG_TREND_DEADBAND_MSG_PER_SEC"
  );

  private static final List<String> METRICS_PROPERTY_NAMES = METRICS_ENV_NAMES.stream()
    .flatMap(name -> Stream.of(name, name.toLowerCase(Locale.ROOT).replace('_', '.')))
    .toList();

  private final Map<String, String> originalProperties = new HashMap<>();

  @BeforeEach
  void saveAndClearProperties() {
    METRICS_PROPERTY_NAMES.forEach(name -> {
      originalProperties.put(name, System.getProperty(name));
      System.clearProperty(name);
    });
  }

  @AfterEach
  void restoreProperties() {
    originalProperties.forEach(MetricsConfigTest::restoreProperty);
  }

  @Test
  void record_holdsAllFields() {
    MetricsConfig c = new MetricsConfig("prometheus", 30_000L, "ingest*,categorize*", "debug-*", true);
    assertEquals("prometheus", c.reporterType());
    assertEquals(30_000L, c.collectionIntervalMs());
    assertEquals("ingest*,categorize*", c.consumerGroupFilter());
    assertEquals("debug-*", c.consumerGroupExclude());
    assertTrue(c.jvmMetricsEnabled());
  }

  @Test
  void consumerGroupExclude_defaultsToEmptyString_whenConstructedDirectly() {
    MetricsConfig c = new MetricsConfig("prometheus", 30_000L, "*", "", false);
    assertEquals("", c.consumerGroupExclude());
  }

  @Test
  void isEnabled_returnsFalseForNoneOrBlankReporter() {
    assertFalse(new MetricsConfig("none", 60_000L, "*", "", false).isEnabled());
    assertFalse(new MetricsConfig("", 60_000L, "*", "", false).isEnabled());
    assertFalse(new MetricsConfig(null, 60_000L, "*", "", false).isEnabled());
  }

  @Test
  void isEnabled_returnsTrueForRealReporter() {
    assertTrue(new MetricsConfig("prometheus", 60_000L, "*", "", false).isEnabled());
    assertTrue(new MetricsConfig("datadog", 60_000L, "*", "", false).isEnabled());
    assertTrue(new MetricsConfig("otlp", 60_000L, "*", "", false).isEnabled());
  }

  @Test
  void fromEnvironment_producesNonNullRecord() {
    MetricsConfig c = MetricsConfig.fromEnvironment();
    assertNotNull(c);
    assertNotNull(c.reporterType());
    assertNotNull(c.consumerGroupFilter());
    assertNotNull(c.consumerGroupExclude());
  }

  @Test
  void fromEnvironment_excludeIsConsumableByGroupFilter() {
    MetricsConfig c = MetricsConfig.fromEnvironment();
    GroupFilter f = new GroupFilter(c.consumerGroupFilter(), c.consumerGroupExclude());
    assertNotNull(f.includeDescription());
    assertNotNull(f.excludeDescription());
  }

  @Test
  void fromEnvironment_readsEveryFieldFromExactProperties() {
    setProperties(Map.of(
      "METRICS_REPORTER", "prometheus",
      "METRICS_INTERVAL_MS", "15000",
      "METRICS_GROUP_FILTER", "prod-*",
      "METRICS_GROUP_EXCLUDE", "debug-*",
      "METRICS_JVM_ENABLED", "true",
      "LAG_TREND_DEADBAND_MSG_PER_SEC", "2.5"
    ));

    assertConfig(
      MetricsConfig.fromEnvironment(),
      "prometheus", 15_000L, "prod-*", "debug-*", true, 2.5
    );
  }

  @Test
  void fromEnvironment_readsEveryFieldFromDottedProperties() {
    setProperties(Map.of(
      "metrics.reporter", "otlp",
      "metrics.interval.ms", "25000",
      "metrics.group.filter", "orders-*",
      "metrics.group.exclude", "orders-shadow",
      "metrics.jvm.enabled", "true",
      "lag.trend.deadband.msg.per.sec", "3.5"
    ));

    assertConfig(
      MetricsConfig.fromEnvironment(),
      "otlp", 25_000L, "orders-*", "orders-shadow", true, 3.5
    );
  }

  @Test
  void fromEnvironment_exactPropertiesWinOverDottedProperties() {
    setProperties(Map.ofEntries(
      Map.entry("METRICS_REPORTER", "datadog"),
      Map.entry("metrics.reporter", "otlp"),
      Map.entry("METRICS_INTERVAL_MS", "30000"),
      Map.entry("metrics.interval.ms", "31000"),
      Map.entry("METRICS_GROUP_FILTER", "exact-*"),
      Map.entry("metrics.group.filter", "dotted-*"),
      Map.entry("METRICS_GROUP_EXCLUDE", "exact-debug-*"),
      Map.entry("metrics.group.exclude", "dotted-debug-*"),
      Map.entry("METRICS_JVM_ENABLED", "false"),
      Map.entry("metrics.jvm.enabled", "true"),
      Map.entry("LAG_TREND_DEADBAND_MSG_PER_SEC", "4.5"),
      Map.entry("lag.trend.deadband.msg.per.sec", "5.5")
    ));

    assertConfig(
      MetricsConfig.fromEnvironment(),
      "datadog", 30_000L, "exact-*", "exact-debug-*", false, 4.5
    );
  }

  @Test
  void fromEnvironment_blankPropertiesUseDefaults() {
    setProperties(Map.ofEntries(
      Map.entry("METRICS_REPORTER", " "),
      Map.entry("metrics.reporter", " "),
      Map.entry("METRICS_INTERVAL_MS", " "),
      Map.entry("metrics.interval.ms", " "),
      Map.entry("METRICS_GROUP_FILTER", " "),
      Map.entry("metrics.group.filter", " "),
      Map.entry("METRICS_GROUP_EXCLUDE", " "),
      Map.entry("metrics.group.exclude", " "),
      Map.entry("METRICS_JVM_ENABLED", " "),
      Map.entry("metrics.jvm.enabled", " "),
      Map.entry("LAG_TREND_DEADBAND_MSG_PER_SEC", " "),
      Map.entry("lag.trend.deadband.msg.per.sec", " ")
    ));

    assertConfig(
      MetricsConfig.fromEnvironment(),
      "none", 60_000L, "*", "", false, 1.0
    );
  }

  @Test
  void fromEnvironment_invalidBooleanUsesDefault() {
    System.setProperty("METRICS_JVM_ENABLED", "not-a-boolean");

    assertConfig(
      MetricsConfig.fromEnvironment(),
      "none", 60_000L, "*", "", false, 1.0
    );
  }

  @Test
  void fromEnvironment_environmentVariablesWinOverProperties() throws Exception {
    ProbeResult result = runProbe(
      Map.ofEntries(
        Map.entry("METRICS_REPORTER", "datadog"),
        Map.entry("metrics.reporter", "otlp"),
        Map.entry("METRICS_INTERVAL_MS", "31000"),
        Map.entry("metrics.interval.ms", "32000"),
        Map.entry("METRICS_GROUP_FILTER", "property-*"),
        Map.entry("metrics.group.filter", "dotted-*"),
        Map.entry("METRICS_GROUP_EXCLUDE", "property-debug-*"),
        Map.entry("metrics.group.exclude", "dotted-debug-*"),
        Map.entry("METRICS_JVM_ENABLED", "false"),
        Map.entry("metrics.jvm.enabled", "false"),
        Map.entry("LAG_TREND_DEADBAND_MSG_PER_SEC", "6.5"),
        Map.entry("lag.trend.deadband.msg.per.sec", "7.5")
      ),
      Map.of(
        "METRICS_REPORTER", "prometheus",
        "METRICS_INTERVAL_MS", "12000",
        "METRICS_GROUP_FILTER", "env-*",
        "METRICS_GROUP_EXCLUDE", "env-debug-*",
        "METRICS_JVM_ENABLED", "true",
        "LAG_TREND_DEADBAND_MSG_PER_SEC", "1.5"
      )
    );

    assertEquals("prometheus|12000|env-*|env-debug-*|true|1.5", result.config());
  }

  private static ProbeResult runProbe(
    Map<String, String> properties,
    Map<String, String> environment
  ) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    properties.forEach((name, value) -> command.add("-D" + name + "=" + value));
    command.add("-cp");
    command.add(probeClasspath());
    command.add(MetricsConfigProbe.class.getName());

    ProcessBuilder builder = new ProcessBuilder(command);
    METRICS_ENV_NAMES.forEach(builder.environment()::remove);
    builder.environment().remove("LOG_LEVEL_KLAG");
    builder.environment().putAll(environment);
    builder.redirectErrorStream(true);

    Process process = builder.start();
    FutureTask<byte[]> outputTask = new FutureTask<>(
      () -> process.getInputStream().readAllBytes()
    );
    Thread outputReader = new Thread(outputTask, "metrics-config-probe-output");
    outputReader.setDaemon(true);
    outputReader.start();

    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      String output = new String(outputTask.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
      throw new AssertionError("MetricsConfig probe timed out:\n" + output);
    }
    String output = new String(outputTask.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);

    String config = output.lines()
      .filter(line -> line.startsWith(MetricsConfigProbe.OUTPUT_PREFIX))
      .map(line -> line.substring(MetricsConfigProbe.OUTPUT_PREFIX.length()))
      .findFirst()
      .orElseThrow(() -> new AssertionError("Probe output missing config line:\n" + output));
    return new ProbeResult(config, output);
  }

  private static void setProperties(Map<String, String> properties) {
    properties.forEach(System::setProperty);
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  private static void assertConfig(
    MetricsConfig config,
    String reporter,
    long interval,
    String filter,
    String exclude,
    boolean jvmEnabled,
    double deadband
  ) {
    assertEquals(reporter, config.reporterType());
    assertEquals(interval, config.collectionIntervalMs());
    assertEquals(filter, config.consumerGroupFilter());
    assertEquals(exclude, config.consumerGroupExclude());
    assertEquals(jvmEnabled, config.jvmMetricsEnabled());
    assertEquals(deadband, config.lagTrendDeadband());
  }

  private static String javaExecutable() {
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    return Path.of(
      System.getProperty("java.home"),
      "bin",
      windows ? "java.exe" : "java"
    ).toString();
  }

  private static String probeClasspath() {
    return Stream.of(
        MetricsConfigProbe.class,
        MetricsConfig.class,
        LoggerFactory.class,
        Logger.class,
        Appender.class
      )
      .map(MetricsConfigTest::codeSource)
      .distinct()
      .collect(Collectors.joining(File.pathSeparator));
  }

  private static String codeSource(Class<?> type) {
    try {
      return Path.of(
        type.getProtectionDomain().getCodeSource().getLocation().toURI()
      ).toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Cannot resolve classpath for " + type.getName(), e);
    }
  }

  private record ProbeResult(String config, String output) {}
}
