package io.github.themoah.klag.model;

/**
 * A topic partition whose in-sync replica set is smaller than its full replica set.
 * Topic-level (no consumer_group dimension) — identical for every group consuming the topic.
 *
 * @param topic the topic name
 * @param partition the partition number
 * @param replicaCount total replica count for the partition
 * @param inSyncReplicaCount count of replicas currently in the ISR set
 */
public record UnderReplicatedPartition(
  String topic,
  int partition,
  int replicaCount,
  int inSyncReplicaCount
) {
  public UnderReplicatedPartition {
    if (inSyncReplicaCount < 0 || inSyncReplicaCount > replicaCount) {
      throw new IllegalArgumentException(
        "inSyncReplicaCount must be in [0, replicaCount]: isr=" + inSyncReplicaCount
          + " replicas=" + replicaCount);
    }
  }
}
