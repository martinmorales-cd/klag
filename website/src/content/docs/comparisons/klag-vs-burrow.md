---
title: Klag vs Burrow
description: Klag and Burrow both monitor Kafka consumer lag. Burrow evaluates lag against status rules and sends notifications; Klag exports richer lag metrics to Prometheus, Datadog, and OTLP.
---

Both **Klag** and [Burrow](https://github.com/linkedin/Burrow) monitor Kafka consumer
lag with read-only access. They differ in philosophy: Burrow evaluates lag against
built-in status rules and pushes notifications; Klag exports a richer set of lag metrics
and leaves evaluation and alerting to your observability stack.

## What each tool is

- **Klag** is a lag *metrics exporter*. It polls the Kafka Admin API on an interval and
  publishes metrics to Prometheus, Datadog, or OTLP.
- **Burrow** is a lag *monitor with an evaluation engine*. It classifies each consumer
  group's status (OK / WARN / ERROR) using a sliding-window algorithm and can push
  notifications over email and HTTP. It exposes an HTTP API; Prometheus metrics come via
  a separate exporter.

## At a glance

| | **Klag** | **Burrow** |
|---|---|---|
| Category | Lag metrics exporter | Lag monitor + notifier |
| Runtime | Java / Vert.x / GraalVM | Go |
| Primary output | Prometheus, Datadog, OTLP | HTTP API + notifiers |
| Lag evaluation | In your alerting stack | Built-in status rules |
| License | Apache 2.0 | Apache 2.0 |

## Feature comparison

| Feature | Klag | Burrow |
|---|:---:|:---:|
| Lag in messages | ✅ | ✅ |
| Consumer group state | ✅ | ✅ |
| Lag status evaluation rules | ❌ | ✅ |
| Built-in notifiers (email/HTTP) | ❌ | ✅ |
| Lag velocity (growing/shrinking) | ✅ | ⚠️ status only |
| Time-based lag (ms) + time-to-catch-up | ✅ | ❌ |
| [Hot partition detection](/metrics/hot-partitions/) | ✅ | ❌ |
| [Data-loss / retention alerting](/metrics/data-loss-prevention/) | ✅ | ❌ |
| [Commit staleness](/metrics/overview/) (stuck consumers) | ✅ | ❌ |
| [MCP endpoint for AI agents](/ai/mcp/) | ✅ | ❌ |
| Prometheus native | ✅ | ⚠️ separate exporter |
| Datadog / OTLP native | ✅ | ❌ |
| Read-only DESCRIBE ACLs | ✅ | ✅ |

## When to use which

- **Use Burrow** if you want a self-contained tool that decides "is this group healthy?"
  and pushes a notification, without a metrics pipeline. Its status-window algorithm is
  its signature feature.
- **Use Klag** if you already run Prometheus / Grafana / Datadog and want lag as
  first-class metrics — plus velocity, time-based lag, retention risk, and hot-partition
  signals — that you alert on with your existing rules and dashboards. Klag also exposes
  a read-only [MCP endpoint](/ai/mcp/) for AI agents, which Burrow does not.

Klag was inspired by the archived
[kafka-lag-exporter](/getting-started/migrating-from-kafka-lag-exporter/); if you're
coming from that project, the migration guide maps every metric.

> Spotted something out of date? Open an issue or PR against
> [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
