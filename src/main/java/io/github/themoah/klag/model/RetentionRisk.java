package io.github.themoah.klag.model;

/**
 * Represents the retention risk percentage for a consumer group and topic.
 * Shows what percentage of available messages (retention window) the consumer lag represents.
 *
 * <p>Formula: {@code (lag / (logEndOffset - logStartOffset)) * 100}
 *
 * <p>Values:
 * <ul>
 *   <li>0% - Consumer is caught up</li>
 *   <li>100% - Data loss has occurred (consumer behind logStartOffset)</li>
 * </ul>
 *
 * <p>{@code partition == -1} marks the topic-level aggregate (max percent across partitions);
 * a value {@code >= 0} is a single partition's risk. The reporter omits the {@code partition}
 * tag for the aggregate so topic rollups and per-partition drill-down coexist under one metric.
 *
 * @param consumerGroup the consumer group ID
 * @param topic the topic name
 * @param partition the partition, or {@code -1} for the topic-level aggregate
 * @param percent the retention risk percentage (lag / retention_window * 100)
 */
public record RetentionRisk(
  String consumerGroup,
  String topic,
  int partition,
  double percent
) {
  /** Sentinel partition value marking the topic-level aggregate series. */
  public static final int AGGREGATE = -1;
}
