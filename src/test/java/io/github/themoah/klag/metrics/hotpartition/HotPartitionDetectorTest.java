package io.github.themoah.klag.metrics.hotpartition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.HotPartitionThroughput;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Kafka topic names may legally contain ':', which is also the separator of the internal
 * partition-history key. Detection must never parse that key back into topic/partition.
 */
class HotPartitionDetectorTest {

  private static final String TOPIC = "app:events";

  // sigma 1.0: with only 4 partitions the population z-score is capped at 1.5, so the
  // default 2.0 could never flag an outlier in a fixture this small.
  private static final HotPartitionConfig CONFIG =
      new HotPartitionConfig(true, 1.0, 3, 2, 20);

  private static List<ConsumerGroupLag> lagData(long... logEndOffsets) {
    List<PartitionLag> partitions = new ArrayList<>();
    for (int p = 0; p < logEndOffsets.length; p++) {
      partitions.add(PartitionLag.of(TOPIC, p, logEndOffsets[p], 0, 0, 0, 0));
    }
    return List.of(ConsumerGroupLag.fromPartitions("group", partitions));
  }

  @Test
  void colonInTopicName_throughputDetectionStillWorks() throws InterruptedException {
    HotPartitionDetector detector = new HotPartitionDetector(CONFIG);

    Set<String> firstKeys = detector.recordThroughputSnapshots(lagData(0, 0, 0, 0));
    assertEquals(4, firstKeys.size());

    // Distinct timestamps: the throughput regression needs a non-zero time spread.
    Thread.sleep(20);
    detector.recordThroughputSnapshots(lagData(10, 10, 10, 1000));

    List<HotPartitionThroughput> hot = detector.detectHotPartitionsByThroughput();

    assertEquals(1, hot.size());
    assertEquals(TOPIC, hot.get(0).topic());
    assertEquals(3, hot.get(0).partition());
  }

  @Test
  void colonInTopicName_lagDetectionStillWorks() {
    HotPartitionDetector detector = new HotPartitionDetector(CONFIG);

    // Partition 3 is far behind; partitions 0-2 are caught up.
    List<PartitionLag> partitions = List.of(
      PartitionLag.of(TOPIC, 0, 100, 0, 0, 0, 100),
      PartitionLag.of(TOPIC, 1, 100, 0, 0, 0, 100),
      PartitionLag.of(TOPIC, 2, 100, 0, 0, 0, 100),
      PartitionLag.of(TOPIC, 3, 100, 0, 0, 0, 0));

    List<HotPartitionLag> hot = detector.detectHotPartitionsByLag(
      List.of(ConsumerGroupLag.fromPartitions("group", partitions)));

    assertEquals(1, hot.size());
    assertEquals(TOPIC, hot.get(0).topic());
    assertEquals(3, hot.get(0).partition());
  }

  @Test
  void cleanupUsesTheKeysDetectionReturns() throws InterruptedException {
    HotPartitionDetector detector = new HotPartitionDetector(CONFIG);

    Set<String> activeKeys = detector.recordThroughputSnapshots(lagData(0, 0, 0, 0));
    detector.cleanupStalePartitions(activeKeys);
    assertEquals(4, activeKeys.size());

    Thread.sleep(20);
    detector.recordThroughputSnapshots(lagData(10, 10, 10, 1000));

    // Detection needs 2 samples per partition. If cleanup had dropped the first sample
    // (mismatched key format), only one would remain and nothing could be flagged.
    List<HotPartitionThroughput> hot = detector.detectHotPartitionsByThroughput();
    assertEquals(1, hot.size());
    assertEquals(3, hot.get(0).partition());
  }
}
