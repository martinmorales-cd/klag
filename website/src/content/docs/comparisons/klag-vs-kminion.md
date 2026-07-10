---
title: Klag vs KMinion
description: Klag and KMinion are both Prometheus exporters for Kafka. KMinion covers broader broker/topic metrics; Klag focuses on deeper consumer-lag signals and adds Datadog and OTLP output.
---

**Klag** and [KMinion](https://github.com/redpanda-data/kminion) are both Prometheus
exporters for Kafka. They overlap on consumer-lag basics but emphasize different things:
KMinion casts a wider net across broker/topic/log-dir metrics; Klag goes deeper on
consumer-lag analysis and ships to Datadog and OTLP as well as Prometheus.

## What each tool is

- **Klag** is a consumer-lag exporter focused on lag depth: velocity, time-based lag,
  retention risk, hot partitions, and commit staleness, exported to Prometheus, Datadog,
  or OTLP. Its cluster-adjacent scope is limited to partition offsets,
  under-replicated partitions, and topic partition counts; it has no broker or log-dir
  metrics.
- **KMinion** is a broad Prometheus exporter covering consumer-group lag plus broker,
  topic, and log-directory metrics. It also runs optional end-to-end
  produce/consume probes to measure roundtrip latency.

## At a glance

| | **Klag** | **KMinion** |
|---|---|---|
| Category | Consumer-lag exporter | General Prometheus exporter |
| Runtime | Java / Vert.x / GraalVM | Go |
| Primary output | Prometheus, Datadog, OTLP | Prometheus |
| Emphasis | Deep consumer-lag signals | Broad cluster metrics |
| License | Apache 2.0 | MIT |

## Feature comparison

| Feature | Klag | KMinion |
|---|:---:|:---:|
| Lag in messages | ✅ | ✅ |
| Consumer group state | ✅ | ✅ |
| Topic / partition metrics | ⚠️ limited | ✅ broad |
| Broker / log-dir metrics | ❌ | ✅ |
| End-to-end latency probes | ❌ | ✅ |
| Lag velocity (growing/shrinking) | ✅ | ❌ |
| Time-based lag (ms) + time-to-catch-up | ✅ | ❌ |
| [Hot partition detection](/metrics/hot-partitions/) | ✅ | ❌ |
| [Data-loss / retention alerting](/metrics/data-loss-prevention/) | ✅ | ❌ |
| [Commit staleness](/metrics/overview/) (stuck consumers) | ✅ | ❌ |
| [MCP endpoint for AI agents](/ai/mcp/) | ✅ | ❌ |
| Datadog / OTLP native | ✅ | ❌ |
| Read-only DESCRIBE ACLs | ✅ | ⚠️ writes for e2e probes |
| GraalVM native image (~44 MB RSS) | ✅ | ❌ |

## When to use which

- **Use KMinion** if you want one exporter for broad cluster health — broker metrics,
  topic configs, log-dir sizes, and optional end-to-end latency probes — all in
  Prometheus.
- **Use Klag** if consumer lag is the priority and you want the deeper signals (velocity,
  time-based lag, retention risk, hot partitions, commit staleness) plus Datadog/OTLP
  output and an [MCP endpoint](/ai/mcp/) for AI agents. Note KMinion's end-to-end probes
  produce and consume test messages, so it needs write access; Klag is strictly
  read-only (`DESCRIBE`).

The two aren't mutually exclusive — some teams run KMinion for broker/topic breadth and
Klag for consumer-lag depth.

> Spotted something out of date? Open an issue or PR against
> [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
