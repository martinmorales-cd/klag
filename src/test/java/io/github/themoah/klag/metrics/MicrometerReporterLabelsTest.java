package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.LagMs;
import io.github.themoah.klag.model.RetentionRisk;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the issue #55 label additions:
 * <ul>
 *   <li>{@code klag.consumer.lag.sum/.max/.min} carry a {@code topic} label (per-group-per-topic).</li>
 *   <li>{@code klag.consumer.lag.ms} emits both the topic-level aggregate (no {@code partition}
 *       tag) and per-partition series.</li>
 *   <li>{@code klag.consumer.lag.retention_percent} does the same.</li>
 * </ul>
 */
class MicrometerReporterLabelsTest {

  /** payments consuming two topics: orders (p0 lag=10, p1 lag=20) and shipments (p0 lag=5). */
  private static ConsumerGroupLag twoTopicGroup() {
    return ConsumerGroupLag.fromPartitions("payments", List.of(
      PartitionLag.of("orders", 0, 100, 0, 0, 0, 90),    // lag 10
      PartitionLag.of("orders", 1, 100, 0, 0, 0, 80),    // lag 20
      PartitionLag.of("shipments", 0, 100, 0, 0, 0, 95)  // lag 5
    ));
  }

  private static boolean hasTag(Meter m, String key) {
    return m.getId().getTags().stream().anyMatch(t -> t.getKey().equals(key));
  }

  @Test
  void lagSumMaxMinCarryTopicLabel() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportLag(List.of(twoTopicGroup()), null);

    // One series per (group, topic), each tagged with topic.
    assertEquals(2, registry.find("klag.consumer.lag.sum").gauges().size(),
      "lag.sum must be emitted per topic");
    Gauge ordersSum = registry.find("klag.consumer.lag.sum")
      .tag("consumer_group", "payments").tag("topic", "orders").gauge();
    assertNotNull(ordersSum, "lag.sum must carry a topic label");
    assertEquals(30.0, ordersSum.value(), "orders sum = 10 + 20");
    assertEquals(5.0, registry.find("klag.consumer.lag.sum")
      .tag("topic", "shipments").gauge().value(), "shipments sum = 5");

    assertEquals(20.0, registry.find("klag.consumer.lag.max")
      .tag("topic", "orders").gauge().value(), "orders max = 20");
    assertEquals(10.0, registry.find("klag.consumer.lag.min")
      .tag("topic", "orders").gauge().value(), "orders min = 10");

    // No group-level (topic-less) aggregate remains.
    assertTrue(registry.find("klag.consumer.lag.sum").gauges().stream().allMatch(g -> hasTag(g, "topic")),
      "every lag.sum series must be topic-scoped");
  }

  @Test
  void lagMsEmitsAggregateAndPerPartition() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportLagMs(List.of(
      new LagMs("payments", "orders", LagMs.AGGREGATE, 30, 2000), // topic aggregate
      new LagMs("payments", "orders", 0, 10, 1500),
      new LagMs("payments", "orders", 1, 20, 2000)
    ), null);

    // Per-partition series carry the partition tag.
    assertNotNull(registry.find("klag.consumer.lag.ms")
      .tag("topic", "orders").tag("partition", "0").gauge(), "per-partition lag.ms present");
    assertNotNull(registry.find("klag.consumer.lag.ms")
      .tag("topic", "orders").tag("partition", "1").gauge(), "per-partition lag.ms present");

    // The aggregate carries no partition tag.
    long aggregates = registry.find("klag.consumer.lag.ms").gauges().stream()
      .filter(g -> !hasTag(g, "partition")).count();
    assertEquals(1, aggregates, "exactly one topic-level lag.ms series without a partition tag");
  }

  @Test
  void retentionEmitsAggregateAndPerPartition() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportRetentionPercent(List.of(
      new RetentionRisk("payments", "orders", RetentionRisk.AGGREGATE, 50.0),
      new RetentionRisk("payments", "orders", 0, 30.0),
      new RetentionRisk("payments", "orders", 1, 50.0)
    ), null);

    assertNotNull(registry.find("klag.consumer.lag.retention_percent")
      .tag("partition", "1").gauge(), "per-partition retention present");
    assertNull(registry.find("klag.consumer.lag.retention_percent")
      .tag("consumer_group", "nope").gauge(), "no spurious series");
    long aggregates = registry.find("klag.consumer.lag.retention_percent").gauges().stream()
      .filter(g -> !hasTag(g, "partition")).count();
    assertEquals(1, aggregates, "exactly one topic-level retention series without a partition tag");
  }
}
