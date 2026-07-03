package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.themoah.klag.model.UnderReplicatedPartition;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the under-replicated partition (ISR) gauge: value is the missing-replica count,
 * tags are topic+partition only (no consumer_group), and the two-phase stale-gauge cleanup
 * retires the series once a partition is no longer under-replicated.
 */
class MicrometerReporterUnderReplicatedTest {

  @Test
  void reportsGaugeWithMissingReplicaCountAsValue() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportUnderReplicatedPartitions(
      List.of(new UnderReplicatedPartition("orders", 2, 3, 1)), null);

    Gauge g = registry.find("klag.partition.under_replicated")
      .tag("topic", "orders").tag("partition", "2").gauge();
    assertNotNull(g);
    assertEquals(2.0, g.value(), "missing replicas = replicaCount(3) - inSyncReplicaCount(1)");
  }

  @Test
  void noConsumerGroupTag() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportUnderReplicatedPartitions(
      List.of(new UnderReplicatedPartition("orders", 0, 3, 2)), null);

    assertNull(registry.find("klag.partition.under_replicated").tag("consumer_group", "").gauge(),
      "under-replicated partition metric is topic-level, must not carry a consumer_group tag");
    assertNotNull(registry.find("klag.partition.under_replicated")
      .tag("topic", "orders").tag("partition", "0").gauge());
  }

  @Test
  void staleGaugeRemovedAfterTwoCleanupCyclesWhenNoLongerUnderReplicated() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    Set<String> cycle1 = new HashSet<>();
    reporter.reportUnderReplicatedPartitions(
      List.of(new UnderReplicatedPartition("orders", 0, 3, 2)), cycle1);
    reporter.cleanupStaleGauges(cycle1);

    assertNotNull(registry.find("klag.partition.under_replicated")
      .tag("topic", "orders").tag("partition", "0").gauge(), "still present right after first cycle");

    // Partition recovered: no longer reported, so activeKeys is empty for two more cycles.
    reporter.cleanupStaleGauges(Set.of()); // phase 1: mark for deletion
    assertNotNull(registry.find("klag.partition.under_replicated")
      .tag("topic", "orders").tag("partition", "0").gauge(), "survives the mark phase");

    reporter.cleanupStaleGauges(Set.of()); // phase 2: delete
    assertNull(registry.find("klag.partition.under_replicated")
      .tag("topic", "orders").tag("partition", "0").gauge(), "removed after two consecutive misses");
  }
}
