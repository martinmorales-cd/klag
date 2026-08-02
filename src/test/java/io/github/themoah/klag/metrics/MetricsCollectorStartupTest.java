package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Startup and empty-cycle behaviour of the collector: a broker that is unreachable at boot
 * must degrade rather than fail startup (which exits the process), and a cycle that sees no
 * groups must still run cycle-end cleanup and refresh the MCP snapshot.
 */
@ExtendWith(VertxExtension.class)
class MetricsCollectorStartupTest {

  /** Kafka that answers nothing: every admin call fails. */
  private static class DeadKafka implements KafkaClientService {
    private static <T> Future<T> down() {
      return Future.failedFuture(new RuntimeException("broker down"));
    }

    @Override public Future<Set<String>> listTopics() { return down(); }
    @Override public Future<List<PartitionInfo>> listPartitions(String topic) { return down(); }
    @Override public Future<List<PartitionOffsets>> getLogEndOffsets(String topic) { return down(); }
    @Override public Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId) { return down(); }
    @Override public Future<String> describeCluster() { return down(); }
    @Override public Future<Set<String>> listConsumerGroups() { return down(); }
    @Override public Future<Map<String, ConsumerGroupState>> describeConsumerGroups(Set<String> groupIds) { return down(); }
    @Override public Future<Map<String, Long>> getTopicRetentionMs(Set<String> topics) { return down(); }
    @Override public Future<Void> close() { return Future.succeededFuture(); }
  }

  /** Healthy Kafka with no consumer groups at all. */
  private static class EmptyKafka implements KafkaClientService {
    @Override public Future<Set<String>> listTopics() { return Future.succeededFuture(Set.of()); }
    @Override public Future<List<PartitionInfo>> listPartitions(String topic) { return Future.succeededFuture(List.of()); }
    @Override public Future<List<PartitionOffsets>> getLogEndOffsets(String topic) { return Future.succeededFuture(List.of()); }
    @Override public Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId) {
      return Future.succeededFuture(new ConsumerGroupOffsets(groupId, Map.of()));
    }
    @Override public Future<String> describeCluster() { return Future.succeededFuture("cluster"); }
    @Override public Future<Set<String>> listConsumerGroups() { return Future.succeededFuture(Set.of()); }
    @Override public Future<Map<String, ConsumerGroupState>> describeConsumerGroups(Set<String> groupIds) {
      return Future.succeededFuture(Map.of());
    }
    @Override public Future<Map<String, Long>> getTopicRetentionMs(Set<String> topics) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Void> close() { return Future.succeededFuture(); }
  }

  private static MetricsCollector collector(Vertx vertx, KafkaClientService kafka) {
    return new MetricsCollector(vertx, kafka,
      new MicrometerReporter(new SimpleMeterRegistry()), 60_000, "*");
  }

  @Test
  void startSucceedsWhenKafkaIsDown(Vertx vertx, VertxTestContext ctx) {
    MetricsCollector collector = collector(vertx, new DeadKafka());
    collector.start()
      .onComplete(ctx.succeeding(v -> collector.stop()
        .onComplete(ctx.succeedingThenComplete())));
  }

  @Test
  void emptyCycleRefreshesSnapshot(Vertx vertx, VertxTestContext ctx) {
    SnapshotStore store = new SnapshotStore();
    MetricsCollector collector = collector(vertx, new EmptyKafka());
    collector.setSnapshotStore(store);

    collector.collectOnce()
      .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
        MetricsSnapshot snap = store.latest().orElseThrow();
        assertTrue(snap.groups().isEmpty());
        assertEquals(0, snap.hotPartitionsByThroughput().size());
        ctx.completeNow();
      })));
  }
}
