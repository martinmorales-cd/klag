---
title: Klag vs Cruise Control
description: Cruise Control rebalances Kafka partitions and heals the cluster; Klag observes consumer lag read-only. Different jobs — you monitor with Klag and remediate with Cruise Control.
---

**Klag** and
[Cruise Control](https://github.com/linkedin/cruise-control) are not alternatives — they
operate on different halves of cluster operations. Klag **observes** (read-only consumer
lag), Cruise Control **acts** (rebalances partitions and heals the cluster). A healthy
setup often uses both: Klag tells you something is wrong, Cruise Control fixes a class of
those problems.

## What each tool is

- **Klag** is a read-only consumer-lag exporter. It never changes cluster state — it
  requires only `DESCRIBE` and emits metrics.
- **Cruise Control** is a cluster-balancing and self-healing tool. It analyzes broker
  load and partition distribution and executes **partition reassignments** to rebalance
  data, add/remove brokers, and fix skew. It performs write/admin operations on the
  cluster.

## At a glance

| | **Klag** | **Cruise Control** |
|---|---|---|
| Category | Lag observer (read-only) | Cluster rebalancer (read-write) |
| Acts on the cluster | ❌ (metrics only) | ✅ (reassigns partitions) |
| Consumer lag | ✅ | ❌ (broker-load focus) |
| Kafka permissions | `DESCRIBE` only | Admin / alter |
| Runtime | Java / Vert.x / GraalVM | Java |
| License | Apache 2.0 | BSD 2-Clause |

## Different problems

| Question | Tool |
|---|---|
| "Is a consumer group falling behind?" | **Klag** |
| "Will lag exceed retention before we catch up?" | **Klag** |
| "Are partitions unevenly distributed across brokers?" | **Cruise Control** |
| "Rebalance the cluster / drain a broker" | **Cruise Control** |

Klag's [hot partition detection](/metrics/hot-partitions/) can flag a partition with
abnormal throughput — a *symptom* — but Klag will never move a partition. Remediating
broker/partition skew is exactly Cruise Control's job.

## When to use which

- **Use Cruise Control** to *fix* broker imbalance and partition skew, or to automate
  broker add/remove and self-healing.
- **Use Klag** to *watch* consumer lag and its trajectory (velocity, time-based lag,
  retention risk) and alert before consumers fall too far behind.

Because Klag is strictly read-only, it's safe to run alongside Cruise Control: it can't
interfere with reassignments — it just reports what lag is doing while they happen.

> Spotted something out of date? Open an issue or PR against
> [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
