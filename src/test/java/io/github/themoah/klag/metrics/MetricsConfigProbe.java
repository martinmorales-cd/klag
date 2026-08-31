package io.github.themoah.klag.metrics;

/**
 * Child-process entry point for deterministic environment/property precedence tests.
 */
public final class MetricsConfigProbe {

  static final String OUTPUT_PREFIX = "KLAG_METRICS_CONFIG|";

  private MetricsConfigProbe() {}

  public static void main(String[] args) {
    MetricsConfig config = MetricsConfig.fromEnvironment();
    System.out.println(OUTPUT_PREFIX + String.join(
      "|",
      config.reporterType(),
      Long.toString(config.collectionIntervalMs()),
      config.consumerGroupFilter(),
      config.consumerGroupExclude(),
      Boolean.toString(config.jvmMetricsEnabled()),
      Double.toString(config.lagTrendDeadband())
    ));
  }
}
