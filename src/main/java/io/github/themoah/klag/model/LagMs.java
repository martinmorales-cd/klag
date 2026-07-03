package io.github.themoah.klag.model;

/**
 * Lag in milliseconds for a consumer group and topic (or a single partition).
 * Uses Kafka log start/end timestamps when available; otherwise bounded poll-time estimation.
 *
 * <p>{@code partition == -1} marks the topic-level aggregate (max lag_ms across partitions);
 * a value {@code >= 0} is a single partition's estimate. The reporter omits the {@code partition}
 * tag for the aggregate so topic rollups and per-partition drill-down coexist under one metric.
 *
 * @param consumerGroup the consumer group ID
 * @param topic the topic name
 * @param partition the partition, or {@code -1} for the topic-level aggregate
 * @param lagMessages the current lag in messages
 * @param lagMs the lag in milliseconds
 */
public record LagMs(
  String consumerGroup,
  String topic,
  int partition,
  long lagMessages,
  long lagMs
) {
  /** Sentinel partition value marking the topic-level aggregate series. */
  public static final int AGGREGATE = -1;
}
