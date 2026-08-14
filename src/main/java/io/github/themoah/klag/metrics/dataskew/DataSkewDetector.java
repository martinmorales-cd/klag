package io.github.themoah.klag.metrics.dataskew;

import io.github.themoah.klag.model.PartitionOffsets;
import io.github.themoah.klag.model.TopicSizeSkew;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores retained-size imbalance across a topic's partitions as {@code max / mean}.
 *
 * <p>{@code retained = max(0, logEndOffset − logStartOffset)}. A perfectly even topic scores
 * {@code 1.0}; a topic whose fullest partition holds twice the average scores {@code 2.0}.
 * Empty topics (mean retained = 0) score {@code 1.0}. Topics with fewer than
 * {@code minPartitions} partitions are skipped.
 */
public final class DataSkewDetector {

  private DataSkewDetector() {}

  /**
   * Computes a size-skew score per topic from already-fetched partition offsets.
   *
   * @param partitions partition offsets observed this cycle (any mix of topics)
   * @param minPartitions topics with fewer partitions than this are omitted
   * @return one {@link TopicSizeSkew} per eligible topic
   */
  public static List<TopicSizeSkew> detect(Collection<PartitionOffsets> partitions, int minPartitions) {
    Map<String, List<Long>> retainedByTopic = new LinkedHashMap<>();
    for (PartitionOffsets po : partitions) {
      long retained = Math.max(0L, po.logEndOffset() - po.logStartOffset());
      retainedByTopic.computeIfAbsent(po.topic(), k -> new ArrayList<>()).add(retained);
    }

    List<TopicSizeSkew> result = new ArrayList<>();
    for (var entry : retainedByTopic.entrySet()) {
      List<Long> sizes = entry.getValue();
      if (sizes.size() < minPartitions) {
        continue;
      }
      result.add(new TopicSizeSkew(entry.getKey(), ratio(sizes)));
    }
    return result;
  }

  private static double ratio(List<Long> sizes) {
    long max = 0L;
    double sum = 0.0;
    for (long size : sizes) {
      max = Math.max(max, size);
      sum += size;
    }
    if (sum == 0.0) {
      return 1.0;
    }
    double mean = sum / sizes.size();
    return max / mean;
  }
}
