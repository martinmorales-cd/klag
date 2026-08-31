package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Size-skew gauges are gated by {@code DATA_SKEW_ENABLED} (default false). When on, the
 * collector scores topics from already-fetched partition offsets — no extra Kafka calls.
 */
@ExtendWith(VertxExtension.class)
class MetricsCollectorDataSkewTest {

  private static final String ENABLED = "DATA_SKEW_ENABLED";

  @AfterEach
  void clearFlag() {
    System.clearProperty(ENABLED);
  }

  /** Two partitions, retained 200 and 0 → max/mean = 2.0. */
  private static class FakeKafka implements KafkaClientService {
    @Override public Future<Set<String>> listTopics() { return Future.succeededFuture(Set.of("orders")); }
    @Override public Future<List<PartitionInfo>> listPartitions(String topic) { return Future.succeededFuture(List.of()); }
    @Override public Future<List<PartitionOffsets>> getLogEndOffsets(String topic) {
      return Future.succeededFuture(List.of(
        new PartitionOffsets(topic, 0, 200, 0, 0, 200, 0, 3, 3),
        new PartitionOffsets(topic, 1, 0, 0, 0, 0, 0, 3, 3)));
    }
    @Override public Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId) {
      return Future.succeededFuture(new ConsumerGroupOffsets(groupId, Map.of(
        new TopicPartitionKey("orders", 0), 40L,
        new TopicPartitionKey("orders", 1), 0L)));
    }
    @Override public Future<String> describeCluster() { return Future.succeededFuture("cluster"); }
    @Override public Future<Set<String>> listConsumerGroups() { return Future.succeededFuture(Set.of("payments")); }
    @Override public Future<Map<String, ConsumerGroupState>> describeConsumerGroups(Set<String> groupIds) {
      return Future.succeededFuture(groupIds.stream().collect(
        Collectors.toMap(id -> id, id -> new ConsumerGroupState(id, State.STABLE))));
    }
    @Override public Future<Map<String, Long>> getTopicRetentionMs(Set<String> topics) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Void> close() { return Future.succeededFuture(); }
  }

  @Test
  void disabledByDefault_emitsNoGauge(Vertx vertx, VertxTestContext ctx) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SnapshotStore store = new SnapshotStore();
    MetricsCollector collector = new MetricsCollector(vertx, new FakeKafka(),
      new MicrometerReporter(registry), 60_000, "*");
    collector.setSnapshotStore(store);

    collector.collectOnce().onComplete(ctx.succeeding(v -> ctx.verify(() -> {
      assertNull(registry.find("klag.topic.size_skew").gauge(),
        "size-skew is opt-in; default DATA_SKEW_ENABLED=false emits nothing");
      assertTrue(store.latest().orElseThrow().groups().get(0).sizeSkews().isEmpty(),
        "MCP snapshot must not carry size-skew when the flag is off");
      ctx.completeNow();
    })));
  }

  @Test
  void enabled_emitsScaledMaxMeanRatio(Vertx vertx, VertxTestContext ctx) {
    System.setProperty(ENABLED, "true");
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MetricsCollector collector = new MetricsCollector(vertx, new FakeKafka(),
      new MicrometerReporter(registry), 60_000, "*");

    collector.collectOnce().onComplete(ctx.succeeding(v -> ctx.verify(() -> {
      Gauge g = registry.find("klag.topic.size_skew").tag("topic", "orders").gauge();
      assertEquals(200.0, g.value(), "ratio 2.0 stored as 200");
      ctx.completeNow();
    })));
  }
}
