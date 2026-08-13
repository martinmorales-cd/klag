package io.github.themoah.klag.metrics.dataskew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.PartitionOffsets;
import io.github.themoah.klag.model.TopicSizeSkew;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DataSkewDetectorTest {

  private static final int MIN_PARTITIONS = 2;

  private static PartitionOffsets offsets(String topic, int partition, long logEnd, long logStart) {
    return new PartitionOffsets(topic, partition, logEnd, logStart, 0L, logEnd, 0L, 3, 3);
  }

  private static Map<String, Double> byTopic(List<TopicSizeSkew> skews) {
    return skews.stream().collect(Collectors.toMap(TopicSizeSkew::topic, TopicSizeSkew::ratio));
  }

  @Test
  void evenTopic_ratioIsOne() {
    List<TopicSizeSkew> result = DataSkewDetector.detect(List.of(
      offsets("orders", 0, 100, 0),
      offsets("orders", 1, 100, 0)
    ), MIN_PARTITIONS);

    assertEquals(1, result.size());
    assertEquals("orders", result.get(0).topic());
    assertEquals(1.0, result.get(0).ratio(), 1e-9);
  }

  @Test
  void twoFullOneEmpty_ratioIsOnePointFive() {
    List<TopicSizeSkew> result = DataSkewDetector.detect(List.of(
      offsets("orders", 0, 100, 0),
      offsets("orders", 1, 100, 0),
      offsets("orders", 2, 0, 0)
    ), MIN_PARTITIONS);

    assertEquals(1, result.size());
    assertEquals(1.5, result.get(0).ratio(), 1e-9);
  }

  @Test
  void allEmpty_ratioIsOne() {
    List<TopicSizeSkew> result = DataSkewDetector.detect(List.of(
      offsets("empty", 0, 0, 0),
      offsets("empty", 1, 0, 0)
    ), MIN_PARTITIONS);

    assertEquals(1, result.size());
    assertEquals(1.0, result.get(0).ratio(), 1e-9);
  }

  @Test
  void singlePartition_skipped() {
    List<TopicSizeSkew> result = DataSkewDetector.detect(List.of(
      offsets("solo", 0, 1000, 0)
    ), MIN_PARTITIONS);

    assertTrue(result.isEmpty());
  }

  @Test
  void mixedTopics_scoresIndependently() {
    Map<String, Double> byTopic = byTopic(DataSkewDetector.detect(List.of(
      offsets("even", 0, 50, 0),
      offsets("even", 1, 50, 0),
      offsets("skewed", 0, 200, 0),
      offsets("skewed", 1, 0, 0),
      offsets("solo", 0, 999, 0)
    ), MIN_PARTITIONS));

    assertEquals(2, byTopic.size());
    assertEquals(1.0, byTopic.get("even"), 1e-9);
    assertEquals(2.0, byTopic.get("skewed"), 1e-9);
  }

  @Test
  void negativeRetained_clampedToZero() {
    List<TopicSizeSkew> result = DataSkewDetector.detect(List.of(
      offsets("orders", 0, 100, 0),
      offsets("orders", 1, 10, 50)
    ), MIN_PARTITIONS);

    assertEquals(1, result.size());
    assertEquals(2.0, result.get(0).ratio(), 1e-9);
  }
}
