package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.UnderReplicatedPartition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies two-phase stale-gauge cleanup removes large batches from the registry in O(1)
 * per delete (HeldGauge retains the Meter reference) — regression for issue #68.
 */
class MicrometerReporterCleanupTest {

  private static final int BATCH_SIZE = 2000;
  private static final long DELETE_BUDGET_MS = 2000L;

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void largeBatchCleanupRemovesAllMetersWithinBudget() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    List<UnderReplicatedPartition> partitions = new ArrayList<>(BATCH_SIZE);
    for (int i = 0; i < BATCH_SIZE; i++) {
      partitions.add(new UnderReplicatedPartition("topic-" + (i / 10), i % 10, 3, 2));
    }

    Set<String> cycle1 = new HashSet<>();
    reporter.reportUnderReplicatedPartitions(partitions, cycle1);
    reporter.cleanupStaleGauges(cycle1);

    assertEquals(BATCH_SIZE, registry.find("klag.partition.under_replicated").gauges().size(),
      "all gauges registered after first cycle");

    // Two empty cycles: mark, then delete (same contract as other cleanup tests).
    reporter.cleanupStaleGauges(Set.of());

    long deleteStartNanos = System.nanoTime();
    reporter.cleanupStaleGauges(Set.of());
    long deleteMs = (System.nanoTime() - deleteStartNanos) / 1_000_000L;

    assertNull(registry.find("klag.partition.under_replicated").gauge(),
      "all under-replicated gauges removed after two-phase cleanup");
    assertEquals(0, registry.find("klag.partition.under_replicated").gauges().size());
    assertTrue(deleteMs < DELETE_BUDGET_MS,
      "deleting " + BATCH_SIZE + " gauges must finish under " + DELETE_BUDGET_MS
        + "ms (O(1) remove); took " + deleteMs + "ms");
  }
}
