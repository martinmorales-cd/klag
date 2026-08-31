package io.github.themoah.klag.metrics.dataskew;

import io.github.themoah.klag.config.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for topic retained-size skew scoring.
 *
 * @param enabled whether size-skew scoring is enabled (opt-in; default false)
 * @param minPartitions minimum partitions in a topic before a score is emitted (default 2)
 */
public record DataSkewConfig(
  boolean enabled,
  int minPartitions
) {

  private static final Logger log = LoggerFactory.getLogger(DataSkewConfig.class);

  private static final boolean DEFAULT_ENABLED = false;
  private static final int DEFAULT_MIN_PARTITIONS = 2;

  /**
   * Loads configuration from environment variables.
   *
   * <p>Supported environment variables:
   * <ul>
   *   <li>DATA_SKEW_ENABLED - Enable/disable size-skew scoring (default: false)</li>
   *   <li>DATA_SKEW_MIN_PARTITIONS - Minimum partitions per topic (default: 2)</li>
   * </ul>
   */
  public static DataSkewConfig fromEnvironment() {
    boolean enabled = Env.getBool("DATA_SKEW_ENABLED", DEFAULT_ENABLED);
    int minPartitions = Env.getInt("DATA_SKEW_MIN_PARTITIONS", DEFAULT_MIN_PARTITIONS);
    if (minPartitions < 1) {
      log.warn("DATA_SKEW_MIN_PARTITIONS must be >= 1, using default: {}", DEFAULT_MIN_PARTITIONS);
      minPartitions = DEFAULT_MIN_PARTITIONS;
    }
    DataSkewConfig config = new DataSkewConfig(enabled, minPartitions);
    log.info("Data skew config: enabled={}, minPartitions={}", enabled, minPartitions);
    return config;
  }
}
