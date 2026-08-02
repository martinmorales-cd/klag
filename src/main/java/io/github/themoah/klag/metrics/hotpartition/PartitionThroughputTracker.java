package io.github.themoah.klag.metrics.hotpartition;

import io.github.themoah.klag.model.PartitionThroughputSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks throughput history for all partitions across all topics.
 * Used to calculate the rate of log_end_offset growth for hot partition detection.
 */
public class PartitionThroughputTracker {

  private static final Logger log = LoggerFactory.getLogger(PartitionThroughputTracker.class);

  private final Map<String, PartitionThroughputHistory> histories = new ConcurrentHashMap<>();
  private final int bufferSize;
  private final int minSamples;

  public PartitionThroughputTracker(int bufferSize, int minSamples) {
    this.bufferSize = bufferSize;
    this.minSamples = minSamples;
  }

  /**
   * Records a throughput snapshot for a partition.
   *
   * @param topic the topic name
   * @param partition the partition number
   * @param logEndOffset the current log end offset
   */
  public void recordSnapshot(String topic, int partition, long logEndOffset) {
    String key = makeKey(topic, partition);
    PartitionThroughputSnapshot snapshot = new PartitionThroughputSnapshot(
      System.currentTimeMillis(),
      logEndOffset
    );

    histories.computeIfAbsent(key, k ->
      new PartitionThroughputHistory(topic, partition, bufferSize, minSamples)
    ).addSnapshot(snapshot);

    log.trace("Recorded throughput snapshot for {}:{} - offset={}", topic, partition, logEndOffset);
  }

  /**
   * Calculates current throughput rates for all partitions that have enough data,
   * grouped by topic.
   *
   * <p>Topic and partition come from the retained history, never from parsing the map key:
   * topic names may legally contain the key separator.
   *
   * @return map of topic to (partition number to throughput in messages/second)
   */
  public Map<String, Map<Integer, Double>> calculateThroughputsByTopic() {
    Map<String, Map<Integer, Double>> result = new HashMap<>();

    for (PartitionThroughputHistory history : histories.values()) {
      Double throughput = history.calculateThroughput();
      if (throughput != null && throughput >= 0) {
        result.computeIfAbsent(history.topic(), k -> new HashMap<>())
          .put(history.partition(), throughput);
      }
    }

    return result;
  }

  /**
   * Removes stale partition histories that are no longer being tracked.
   *
   * @param activeKeys set of "topic:partition" keys that are currently active
   */
  public void cleanupStalePartitions(Set<String> activeKeys) {
    int sizeBefore = histories.size();
    histories.keySet().retainAll(activeKeys);
    int removed = sizeBefore - histories.size();
    if (removed > 0) {
      log.debug("Cleaned up {} stale partition throughput histories", removed);
    }
  }

  /**
   * Creates a key for the partition history map.
   *
   * <p>The key is opaque: it is only ever compared, never parsed back into topic and
   * partition (topic names may contain ':'). Read topic/partition off the history instead.
   *
   * @param topic the topic name
   * @param partition the partition number
   * @return the key in format "topic:partition"
   */
  public static String makeKey(String topic, int partition) {
    return topic + ":" + partition;
  }
}
