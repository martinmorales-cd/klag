package io.github.themoah.klag.metrics;

import io.github.themoah.klag.config.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for under-replicated partition (ISR) detection.
 *
 * @param enabled whether ISR under-replication detection is enabled
 */
public record IsrConfig(
  boolean enabled
) {

  private static final Logger log = LoggerFactory.getLogger(IsrConfig.class);
  private static final boolean DEFAULT_ENABLED = true;

  /**
   * Loads configuration from environment variables.
   *
   * <p>Supported environment variables:
   * <ul>
   *   <li>ISR_ENABLED - Enable/disable under-replicated partition detection (default: true)</li>
   * </ul>
   */
  public static IsrConfig fromEnvironment() {
    boolean enabled = Env.getBool("ISR_ENABLED", DEFAULT_ENABLED);
    IsrConfig config = new IsrConfig(enabled);
    log.info("ISR config: enabled={}", enabled);
    return config;
  }
}
