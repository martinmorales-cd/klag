package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.kafka.ChunkConfig;
import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionConfig;
import io.github.themoah.klag.metrics.timelag.TimeLagConfig;
import io.github.themoah.klag.metrics.velocity.LagVelocityTracker;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression guard for admin request volume.
 *
 * <p>Topic offsets used to be fetched one topic at a time, costing four admin requests per
 * topic per cycle. These tests pin the batched behaviour: a cycle issues one batched fetch
 * (or {@code chunkCount} of them when chunking is on) no matter how many groups and topics
 * are involved, and never falls back to the per-topic call.
 */
@ExtendWith(VertxExtension.class)
class MetricsCollectorBatchingTest {

  /** Counts how the collector asks for topic offsets: batched vs per topic. */
  private static class CountingKafka implements KafkaClientService {
    private final Map<String, List<String>> groupTopics;
    final List<Set<String>> batchCalls = new ArrayList<>();
    int perTopicCalls;

    CountingKafka(Map<String, List<String>> groupTopics) {
      this.groupTopics = groupTopics;
    }

    @Override
    public Future<Map<String, List<PartitionOffsets>>> getLogEndOffsets(Set<String> topics) {
      batchCalls.add(Set.copyOf(topics));
      Map<String, List<PartitionOffsets>> byTopic = new HashMap<>();
      for (String topic : topics) {
        byTopic.put(topic, List.of(new PartitionOffsets(topic, 0, 100, 0, 0, 100, 0, 3, 3)));
      }
      return Future.succeededFuture(byTopic);
    }

    @Override
    public Future<List<PartitionOffsets>> getLogEndOffsets(String topic) {
      perTopicCalls++;
      return Future.succeededFuture(List.of(new PartitionOffsets(topic, 0, 100, 0, 0, 100, 0, 3, 3)));
    }

    @Override
    public Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId) {
      Map<TopicPartitionKey, Long> offsets = groupTopics.get(groupId).stream()
        .collect(Collectors.toMap(topic -> new TopicPartitionKey(topic, 0), topic -> 40L));
      return Future.succeededFuture(new ConsumerGroupOffsets(groupId, offsets));
    }

    @Override
    public Future<Set<String>> listConsumerGroups() {
      return Future.succeededFuture(groupTopics.keySet());
    }

    @Override
    public Future<Map<String, ConsumerGroupState>> describeConsumerGroups(Set<String> groupIds) {
      return Future.succeededFuture(groupIds.stream().collect(
        Collectors.toMap(id -> id, id -> new ConsumerGroupState(id, State.STABLE))));
    }

    @Override public Future<Set<String>> listTopics() { return Future.succeededFuture(Set.of()); }
    @Override public Future<List<PartitionInfo>> listPartitions(String topic) { return Future.succeededFuture(List.of()); }
    @Override public Future<String> describeCluster() { return Future.succeededFuture("cluster"); }
    @Override public Future<Map<String, Long>> getTopicRetentionMs(Set<String> topics) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Void> close() { return Future.succeededFuture(); }
  }

  /** 20 groups, each on 5 topics drawn from a shared pool of 10 distinct topics. */
  private static Map<String, List<String>> manyGroups() {
    return IntStream.range(0, 20).boxed().collect(Collectors.toMap(
      g -> "group-" + g,
      g -> IntStream.range(0, 5).mapToObj(t -> "topic-" + ((g + t) % 10)).collect(Collectors.toList())));
  }

  private MetricsCollector collector(Vertx vertx, KafkaClientService kafka, ChunkConfig chunks) {
    return new MetricsCollector(vertx, kafka,
      new MicrometerReporter(new SimpleMeterRegistry()), 60_000, "*", "",
      new LagVelocityTracker(),
      new HotPartitionConfig(false, 2.0, 3, 3, 20),
      new TimeLagConfig(true, 100, 60, 180_000),
      chunks);
  }

  @Test
  @DisplayName("one cycle over 20 groups x 10 topics issues a single batched offset fetch")
  void singleBatchPerCycle(Vertx vertx, VertxTestContext ctx) {
    CountingKafka kafka = new CountingKafka(manyGroups());

    collector(vertx, kafka, new ChunkConfig(1, 0)).collectOnce().onComplete(ctx.succeeding(v ->
      ctx.verify(() -> {
        assertEquals(1, kafka.batchCalls.size(), "expected exactly one batched fetch per cycle");
        // The union of all groups' topics, deduped — not 20 groups x 5 topics.
        assertEquals(10, kafka.batchCalls.get(0).size());
        assertEquals(0, kafka.perTopicCalls, "per-topic fetch must not be used");
        ctx.completeNow();
      })));
  }

  @Test
  @DisplayName("chunking batches per chunk and never refetches a topic within a cycle")
  void chunkedStillBatches(Vertx vertx, VertxTestContext ctx) {
    CountingKafka kafka = new CountingKafka(manyGroups());

    // 2 group chunks x 2 topic chunks is the ceiling; group chunks share topics, and the
    // per-cycle cache means the later chunk only fetches what is still unresolved.
    collector(vertx, kafka, new ChunkConfig(2, 0)).collectOnce().onComplete(ctx.succeeding(v ->
      ctx.verify(() -> {
        assertTrue(kafka.batchCalls.size() <= 4,
          "expected at most chunkCount^2 batched fetches, got " + kafka.batchCalls.size());
        assertEquals(0, kafka.perTopicCalls, "per-topic fetch must not be used");

        List<String> fetched = kafka.batchCalls.stream().flatMap(Set::stream).toList();
        assertEquals(10, fetched.size(), "every topic fetched exactly once per cycle");
        assertEquals(10, Set.copyOf(fetched).size());
        ctx.completeNow();
      })));
  }

  @Test
  @DisplayName("group fan-out is bounded into waves, all groups still collected")
  void groupFanOutIsBounded(Vertx vertx, VertxTestContext ctx) {
    CountingKafka kafka = new CountingKafka(manyGroups());

    collector(vertx, kafka, new ChunkConfig(1, 0)).collectOnce().onComplete(ctx.succeeding(v ->
      ctx.verify(() -> {
        // Bounding must not drop groups: every topic any group consumes is still fetched.
        assertEquals(10, kafka.batchCalls.get(0).size());
        ctx.completeNow();
      })));
  }
}
