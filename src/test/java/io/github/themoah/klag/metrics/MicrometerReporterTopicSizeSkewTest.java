package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.themoah.klag.model.TopicSizeSkew;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the topic size-skew gauge: value is max/mean × 100, tags are topic only
 * (no consumer_group or partition), and two-phase stale-gauge cleanup retires deleted topics.
 */
class MicrometerReporterTopicSizeSkewTest {

  @Test
  void reportsGaugeScaledBy100() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportTopicSizeSkew(List.of(new TopicSizeSkew("orders", 1.5)), null);

    Gauge g = registry.find("klag.topic.size_skew").tag("topic", "orders").gauge();
    assertNotNull(g);
    assertEquals(150.0, g.value(), "ratio 1.5 is stored as 150 to preserve two decimal places");
  }

  @Test
  void topicTagOnly() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportTopicSizeSkew(List.of(new TopicSizeSkew("orders", 1.0)), null);

    assertNull(registry.find("klag.topic.size_skew").tag("consumer_group", "").gauge(),
      "size-skew is topic-level, must not carry a consumer_group tag");
    assertNull(registry.find("klag.topic.size_skew").tag("partition", "0").gauge(),
      "size-skew is topic-level, must not carry a partition tag");
    assertNotNull(registry.find("klag.topic.size_skew").tag("topic", "orders").gauge());
  }

  @Test
  void staleGaugeRemovedAfterTwoCleanupCyclesWhenTopicGone() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    Set<String> cycle1 = new HashSet<>();
    reporter.reportTopicSizeSkew(List.of(new TopicSizeSkew("orders", 1.0)), cycle1);
    reporter.cleanupStaleGauges(cycle1);

    assertNotNull(registry.find("klag.topic.size_skew").tag("topic", "orders").gauge(),
      "still present right after first cycle");

    reporter.cleanupStaleGauges(Set.of());
    assertNotNull(registry.find("klag.topic.size_skew").tag("topic", "orders").gauge(),
      "survives the mark phase");

    reporter.cleanupStaleGauges(Set.of());
    assertNull(registry.find("klag.topic.size_skew").tag("topic", "orders").gauge(),
      "removed after two consecutive misses");
  }
}
