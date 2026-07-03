---
title: ISR Monitoring
description: Klag detects partitions whose in-sync replica set has shrunk below the full replica set, an early warning sign of reduced fault tolerance or brewing data loss.
---

Kafka replicates each partition to `replicas` brokers, but only tracks brokers as
"in-sync" (ISR) if they're caught up with the leader. When the ISR set shrinks below the
full replica set, the partition has less fault tolerance than configured — and if it
shrinks to zero, the partition has no durable replica to fail over to.

Klag detects this using topic metadata it already fetches every cycle (`describeTopics`) —
no additional Kafka calls or ACLs beyond what klag already requires.

## Metrics

Reported **only when a partition is under-replicated** (so it stays quiet on a healthy cluster):

| Metric | Description |
|---|---|
| `klag.partition.under_replicated` | Missing in-sync replica count (`replicaCount - inSyncReplicaCount`). |

`klag.partition.under_replicated` has only `topic` and `partition` tags — ISR status is
partition-level and independent of any consumer group.

## Configuration

| Variable | Default | Role |
|---|---|---|
| `ISR_ENABLED` | `true` | Master switch. |

## Scope

Klag flags under-replication (`isr.size() < replicas.size()`) only. It does not compare
against `min.insync.replicas` (that would require an additional `describeConfigs` call and
ACL) — under-replication is a strict superset signal that fires earlier, before a producer
would actually start seeing `NotEnoughReplicasException`.

## Acting on it

A shrinking ISR set usually means a broker is down, overloaded, or falling behind on
replication (disk I/O, network partition, GC pause). Check broker health for the missing
replica(s); if `inSyncReplicaCount` reaches 0, treat it as urgent — a leader failure at that
point loses the partition's unflushed data. Klag's [`diagnose` MCP tool](/ai/mcp/) surfaces
this per consumer group as a WARNING (or CRITICAL when ISR count is 0).
