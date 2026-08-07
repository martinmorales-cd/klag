package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.github.themoah.klag.kafka.KafkaClientService;
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
 * Verifies the metrics collector publishes a snapshot for the MCP layer, and that snapshot
 * publishing is best-effort: a failing store must never break the collection flow.
 */
@ExtendWith(VertxExtension.class)
class MetricsCollectorSnapshotTest {

  /** Minimal fake exposing groups consuming one topic/partition with lag. */
  private static class FakeKafka implements KafkaClientService {
    private final Set<String> groups;
    private final Set<String> failingGroups;

    FakeKafka() {
      this(Set.of("payments"), Set.of());
    }

    FakeKafka(Set<String> groups, Set<String> failingGroups) {
      this.groups = groups;
      this.failingGroups = failingGroups;
    }

    @Override public Future<Set<String>> listTopics() { return Future.succeededFuture(Set.of("orders")); }
    @Override public Future<List<PartitionInfo>> listPartitions(String topic) { return Future.succeededFuture(List.of()); }
    @Override public Future<List<PartitionOffsets>> getLogEndOffsets(String topic) {
      return Future.succeededFuture(List.of(new PartitionOffsets(topic, 0, 100, 0, 0, 100, 0, 3, 3)));
    }
    @Override public Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId) {
      if (failingGroups.contains(groupId)) {
        return Future.failedFuture("injected offset failure for " + groupId);
      }
      return Future.succeededFuture(new ConsumerGroupOffsets(groupId,
        Map.of(new TopicPartitionKey("orders", 0), 40L)));
    }
    @Override public Future<String> describeCluster() { return Future.succeededFuture("cluster"); }
    @Override public Future<Set<String>> listConsumerGroups() { return Future.succeededFuture(groups); }
    @Override public Future<Map<String, ConsumerGroupState>> describeConsumerGroups(Set<String> groupIds) {
      return Future.succeededFuture(groupIds.stream().collect(
        java.util.stream.Collectors.toMap(id -> id, id -> new ConsumerGroupState(id, State.STABLE))));
    }
    @Override public Future<Map<String, Long>> getTopicRetentionMs(Set<String> topics) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Void> close() { return Future.succeededFuture(); }
  }

  private MetricsCollector collector(Vertx vertx, SnapshotStore store) {
    return collector(vertx, store, new FakeKafka());
  }

  private MetricsCollector collector(Vertx vertx, SnapshotStore store, KafkaClientService kafka) {
    MetricsCollector c = new MetricsCollector(vertx, kafka,
      new MicrometerReporter(new SimpleMeterRegistry()), 60_000, "*");
    c.setSnapshotStore(store);
    return c;
  }

  @Test
  void publishesSnapshotAfterCollection(Vertx vertx, VertxTestContext ctx) {
    SnapshotStore store = new SnapshotStore();
    collector(vertx, store).collectOnce()
      .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
        MetricsSnapshot snap = store.latest().orElseThrow();
        assertEquals(1, snap.groups().size());
        assertEquals("payments", snap.groups().get(0).consumerGroup());
        assertEquals(60, snap.groups().get(0).totalLag());
        ctx.completeNow();
      })));
  }

  @Test
  void partialCycleStillPublishesSurvivingGroups(Vertx vertx, VertxTestContext ctx) {
    // One permanently failing group (ACL gap, wedged coordinator) must not freeze the MCP
    // view of every other group while /metrics keeps updating.
    SnapshotStore store = new SnapshotStore();
    FakeKafka kafka = new FakeKafka(Set.of("payments", "broken"), Set.of("broken"));

    collector(vertx, store, kafka).collectOnce()
      .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
        MetricsSnapshot snap = store.latest().orElseThrow();
        assertEquals(1, snap.groups().size());
        assertEquals("payments", snap.groups().get(0).consumerGroup());
        ctx.completeNow();
      })));
  }

  @Test
  void totalFailureKeepsPreviousSnapshotInsteadOfWipingIt(Vertx vertx, VertxTestContext ctx) {
    SnapshotStore store = new SnapshotStore();
    FakeKafka allBroken = new FakeKafka(Set.of("payments"), Set.of("payments"));

    collector(vertx, store).collectOnce()
      .compose(v -> collector(vertx, store, allBroken).collectOnce())
      .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
        // Nothing was collected at all: a wiped agent view is worse than a stale one.
        MetricsSnapshot snap = store.latest().orElseThrow();
        assertEquals(1, snap.groups().size());
        assertEquals("payments", snap.groups().get(0).consumerGroup());
        ctx.completeNow();
      })));
  }

  @Test
  void failingStoreDoesNotBreakCollection(Vertx vertx, VertxTestContext ctx) {
    SnapshotStore throwing = new SnapshotStore() {
      @Override public void set(MetricsSnapshot snapshot) {
        throw new RuntimeException("boom");
      }
    };
    // Collection must still succeed despite the snapshot store throwing.
    collector(vertx, throwing).collectOnce()
      .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
        assertTrue(throwing.latest().isEmpty());
        ctx.completeNow();
      })));
  }
}
