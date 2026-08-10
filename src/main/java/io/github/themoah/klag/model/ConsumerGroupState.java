package io.github.themoah.klag.model;

import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import java.util.Map;

/**
 * Consumer group state information.
 *
 * @param groupId the consumer group ID
 * @param state the current state of the consumer group
 * @param partitionOwners which member instance currently owns each partition; empty for
 *                        Empty/Dead groups. Powers the per-instance member labels on lag metrics.
 */
public record ConsumerGroupState(
  String groupId,
  State state,
  Map<TopicPartitionKey, MemberAssignment> partitionOwners
) {

  /** Defensively copies partitionOwners so the record stays immutable. */
  public ConsumerGroupState {
    partitionOwners = Map.copyOf(partitionOwners);
  }

  /** Backwards-compatible constructor for callers that don't track member ownership. */
  public ConsumerGroupState(String groupId, State state) {
    this(groupId, state, Map.of());
  }

  /**
   * Enumeration of possible consumer group states.
   * Mirrors org.apache.kafka.common.ConsumerGroupState.
   */
  public enum State {
    UNKNOWN,
    PREPARING_REBALANCE,
    COMPLETING_REBALANCE,
    // KIP-848 (new consumer protocol) replaces the two rebalance states above with these.
    // Kept as distinct tags rather than aliased onto the classic ones: separate values match
    // what Kafka reports and keep alert semantics honest.
    ASSIGNING,
    RECONCILING,
    STABLE,
    DEAD,
    EMPTY;

    /**
     * Converts a Kafka group-state enum constant name to this enum.
     *
     * <p>Takes the name rather than {@code org.apache.kafka.common.ConsumerGroupState} on
     * purpose: that class is deprecated for removal in kafka-clients 4.x (superseded by
     * {@code GroupState}), but the Vert.x admin wrapper still returns it. Matching on the
     * name keeps klag off the removal path — when Vert.x switches to {@code GroupState},
     * whose constants carry the same names, this needs no change. Names klag does not know
     * map to UNKNOWN.
     *
     * @param kafkaStateName the Kafka group state's enum constant name, may be null
     * @return the corresponding State enum value, or UNKNOWN if unrecognised
     */
    public static State fromKafkaState(String kafkaStateName) {
      if (kafkaStateName == null) {
        return UNKNOWN;
      }
      try {
        return valueOf(kafkaStateName);
      } catch (IllegalArgumentException e) {
        return UNKNOWN;
      }
    }

    /**
     * Returns a lowercase representation suitable for metric labels.
     *
     * @return the state name in lowercase
     */
    public String toMetricValue() {
      return name().toLowerCase();
    }
  }
}
