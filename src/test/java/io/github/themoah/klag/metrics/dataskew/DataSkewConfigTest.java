package io.github.themoah.klag.metrics.dataskew;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DataSkewConfigTest {

  private static final String MIN_PARTITIONS = "DATA_SKEW_MIN_PARTITIONS";

  @AfterEach
  void clearProperties() {
    System.clearProperty(MIN_PARTITIONS);
  }

  @Test
  void invalidMinimumPartitions_usesDefault() {
    System.setProperty(MIN_PARTITIONS, "0");

    DataSkewConfig config = DataSkewConfig.fromEnvironment();

    assertEquals(2, config.minPartitions());
  }
}
