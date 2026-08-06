package io.github.themoah.klag.kafka;

import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.vertx.core.Future;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service interface for Kafka administrative operations.
 * All methods return Vert.x Futures for async, non-blocking execution.
 */
public interface KafkaClientService {

  /**
   * Lists all topics in the Kafka cluster.
   *
   * @return Future containing set of topic names
   */
  Future<Set<String>> listTopics();

  /**
   * Gets partition information for a specific topic.
   *
   * @param topic the topic name
   * @return Future containing list of partition info for the topic
   */
  Future<List<PartitionInfo>> listPartitions(String topic);

  /**
   * Gets the log end offsets (latest offsets) for all partitions of a topic.
   *
   * @param topic the topic name
   * @return Future containing list of partition offsets
   */
  Future<List<PartitionOffsets>> getLogEndOffsets(String topic);

  /**
   * Gets the log end offsets for all partitions of every given topic.
   *
   * <p>The real client overrides this with a single batched round-trip (one describeTopics
   * plus three listOffsets for the whole set), which is what keeps admin request volume
   * independent of topic count. This default fans out per topic so that alternative
   * implementations — notably test fakes — stay correct without reimplementing anything.
   *
   * @param topics the topic names
   * @return Future containing partition offsets grouped by topic; topics with no metadata
   *     are absent from the map
   */
  default Future<Map<String, List<PartitionOffsets>>> getLogEndOffsets(Set<String> topics) {
    if (topics == null || topics.isEmpty()) {
      return Future.succeededFuture(Map.of());
    }
    List<String> ordered = List.copyOf(topics);
    List<Future<List<PartitionOffsets>>> futures = ordered.stream()
      .map(this::getLogEndOffsets)
      .collect(Collectors.toList());

    return Future.all(futures).map(composite -> {
      Map<String, List<PartitionOffsets>> byTopic = new HashMap<>();
      for (int i = 0; i < composite.size(); i++) {
        byTopic.put(ordered.get(i), composite.resultAt(i));
      }
      return byTopic;
    });
  }

  /**
   * Gets the committed offsets for a consumer group.
   *
   * @param groupId the consumer group ID
   * @return Future containing the consumer group offsets
   */
  Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId);

  /**
   * Describes the Kafka cluster (lightweight health check).
   *
   * @return Future containing cluster ID
   */
  Future<String> describeCluster();

  /**
   * Lists all consumer groups in the Kafka cluster.
   *
   * @return Future containing set of consumer group IDs
   */
  Future<Set<String>> listConsumerGroups();

  /**
   * Describes consumer groups and returns their states.
   *
   * @param groupIds set of consumer group IDs to describe
   * @return Future containing map of group ID to ConsumerGroupState
   */
  Future<Map<String, ConsumerGroupState>> describeConsumerGroups(Set<String> groupIds);

  /**
   * Gets the retention.ms configuration for the specified topics.
   *
   * @param topics set of topic names
   * @return Future containing map of topic name to retention in milliseconds
   */
  Future<Map<String, Long>> getTopicRetentionMs(Set<String> topics);

  /**
   * Closes the underlying Kafka admin client and releases resources.
   *
   * @return Future that completes when the client is closed
   */
  Future<Void> close();
}
