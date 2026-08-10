package io.github.themoah.klag.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.vertx.kafka.admin.ListOffsetsResultInfo;
import io.vertx.kafka.client.common.Node;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.common.TopicPartitionInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the batched offset assembly: one describeTopics + three listOffsets responses
 * spanning many topics are turned into per-topic {@link PartitionOffsets}.
 *
 * <p>Exercises the pure derivation only — no admin client, no broker.
 */
class KafkaClientServiceImplBatchOffsetsTest {

  private static final long NOW = 1_700_000_000_000L;

  private static PartitionInfo partition(String topic, int partition) {
    return new PartitionInfo(topic, partition, 1, List.of(1, 2, 3), List.of(1, 2, 3));
  }

  private static ListOffsetsResultInfo offset(long offset, long timestamp) {
    return new ListOffsetsResultInfo(offset, timestamp, null);
  }

  private static Node node(int id) {
    return new Node(false, "broker-" + id, id, String.valueOf(id), false, 9092, null);
  }

  @Test
  @DisplayName("groups partitions of multiple topics by topic in one pass")
  void assembleOffsets_groupsByTopic() {
    Map<String, List<PartitionInfo>> partitions = Map.of(
      "orders", List.of(partition("orders", 0), partition("orders", 1)),
      "payments", List.of(partition("payments", 0)));

    Map<TopicPartition, ListOffsetsResultInfo> maxTs = new HashMap<>();
    Map<TopicPartition, ListOffsetsResultInfo> earliest = new HashMap<>();
    Map<TopicPartition, ListOffsetsResultInfo> latest = new HashMap<>();
    for (String topic : List.of("orders", "payments")) {
      for (int p = 0; p < ("orders".equals(topic) ? 2 : 1); p++) {
        TopicPartition tp = new TopicPartition(topic, p);
        maxTs.put(tp, offset(90, NOW));
        earliest.put(tp, offset(10, NOW - 60_000));
        latest.put(tp, offset(100, -1));
      }
    }

    Map<String, List<PartitionOffsets>> result =
      KafkaClientServiceImpl.assembleOffsets(partitions, maxTs, earliest, latest);

    assertEquals(2, result.size());
    assertEquals(2, result.get("orders").size());
    assertEquals(1, result.get("payments").size());

    PartitionOffsets po = result.get("payments").get(0);
    assertEquals("payments", po.topic());
    // logEndOffset is always LATEST; the timestamp anchor comes from MAX_TIMESTAMP separately.
    assertEquals(100, po.logEndOffset());
    assertEquals(10, po.logStartOffset());
    assertEquals(90, po.maxTimestampOffset());
    assertEquals(NOW, po.logEndTimestamp());
    assertEquals(3, po.replicaCount());
    assertEquals(3, po.inSyncReplicaCount());
  }

  @Test
  @DisplayName("a partition missing from listOffsets is skipped, its topic still returned")
  void assembleOffsets_skipsMissingPartition() {
    Map<String, List<PartitionInfo>> partitions = Map.of(
      "orders", List.of(partition("orders", 0), partition("orders", 1)));

    TopicPartition present = new TopicPartition("orders", 0);
    Map<TopicPartition, ListOffsetsResultInfo> maxTs = Map.of(present, offset(90, NOW));
    Map<TopicPartition, ListOffsetsResultInfo> earliest =
      Map.of(present, offset(10, NOW - 60_000));
    Map<TopicPartition, ListOffsetsResultInfo> latest = Map.of(present, offset(100, -1));

    Map<String, List<PartitionOffsets>> result =
      KafkaClientServiceImpl.assembleOffsets(partitions, maxTs, earliest, latest);

    assertEquals(1, result.get("orders").size());
    assertEquals(0, result.get("orders").get(0).partition());
  }

  @Test
  @DisplayName("falls back to LATEST when MAX_TIMESTAMP is absent (pre-Kafka 3.0 broker)")
  void assembleOffsets_maxTimestampFallback() {
    Map<String, List<PartitionInfo>> partitions = Map.of("orders", List.of(partition("orders", 0)));
    TopicPartition tp = new TopicPartition("orders", 0);

    Map<String, List<PartitionOffsets>> result = KafkaClientServiceImpl.assembleOffsets(
      partitions,
      Map.of(), // MAX_TIMESTAMP leg failed for the whole cluster
      Map.of(tp, offset(10, NOW - 60_000)),
      Map.of(tp, offset(100, NOW)));

    PartitionOffsets po = result.get("orders").get(0);
    assertEquals(100, po.maxTimestampOffset());
    assertEquals(NOW, po.logEndTimestamp());
  }

  @Test
  @DisplayName("empty partition (earliest -1) normalises to start offset 0, no start timestamp")
  void assembleOffsets_emptyPartition() {
    Map<String, List<PartitionInfo>> partitions = Map.of("orders", List.of(partition("orders", 0)));
    TopicPartition tp = new TopicPartition("orders", 0);

    Map<String, List<PartitionOffsets>> result = KafkaClientServiceImpl.assembleOffsets(
      partitions,
      Map.of(tp, offset(-1, -1)),
      Map.of(tp, offset(-1, -1)),
      Map.of(tp, offset(0, -1)));

    PartitionOffsets po = result.get("orders").get(0);
    assertEquals(0, po.logStartOffset());
    assertEquals(-1, po.logStartTimestamp());
  }

  @Test
  @DisplayName("leaderless partition degrades instead of failing the whole batch")
  void toPartitionInfo_nullLeaderAndReplicas() {
    // Kafka reports a null leader for offline partitions; unguarded this threw inside the
    // describeTopics mapper and would now fail every topic in the batch, not just this one.
    TopicPartitionInfo offline = new TopicPartitionInfo(null, null, 3, null);

    PartitionInfo info = assertDoesNotThrow(
      () -> KafkaClientServiceImpl.toPartitionInfo("orders", offline));

    assertEquals(3, info.partition());
    assertEquals(-1, info.leader());
    assertTrue(info.replicas().isEmpty());
    assertTrue(info.inSyncReplicas().isEmpty());
  }

  @Test
  @DisplayName("healthy partition keeps its leader and replica ids")
  void toPartitionInfo_healthy() {
    TopicPartitionInfo tpi =
      new TopicPartitionInfo(List.of(node(1), node(2)), node(1), 0, List.of(node(1), node(2), node(3)));

    PartitionInfo info = KafkaClientServiceImpl.toPartitionInfo("orders", tpi);

    assertEquals(1, info.leader());
    assertEquals(List.of(1, 2), info.inSyncReplicas());
    assertEquals(List.of(1, 2, 3), info.replicas());
  }
}
