---
title: Klag vs AKHQ
description: AKHQ is a Kafka web UI for browsing topics, messages, and groups; Klag is a headless consumer-lag metrics exporter. They solve different problems and are often run together.
---

**Klag** and [AKHQ](https://github.com/tchiotludo/akhq) are frequently mentioned
together but do different jobs. AKHQ is an interactive **web UI** for exploring a Kafka
cluster; Klag is a **headless metrics exporter** for consumer lag. AKHQ shows you lag in
a browser right now; Klag turns lag into time-series metrics you alert and trend on.

## What each tool is

- **Klag** is a lag metrics exporter. No UI — it publishes metrics to Prometheus,
  Datadog, or OTLP for dashboards and alerting.
- **AKHQ** is a Kafka web UI. It lets you browse and search topics and messages, view
  and manage consumer groups (including resetting offsets), inspect schemas and ACLs, and
  see current lag interactively.

## At a glance

| | **Klag** | **AKHQ** |
|---|---|---|
| Category | Lag metrics exporter | Web UI / console |
| Interface | Metrics endpoint | Interactive browser UI |
| Message browsing | ❌ | ✅ |
| Time-series lag & alerting | ✅ | ❌ (point-in-time view) |
| Runtime | Java / Vert.x / GraalVM | Java / Micronaut |
| License | Apache 2.0 | Apache 2.0 |

## Feature comparison

| Feature | Klag | AKHQ |
|---|:---:|:---:|
| Browse topics & messages | ❌ | ✅ |
| Manage groups / reset offsets | ❌ (read-only) | ✅ |
| Schema registry & ACL views | ❌ | ✅ |
| Current lag in a UI | ❌ | ✅ |
| Lag as time-series metrics | ✅ | ❌ |
| Lag velocity, [time-based lag](/metrics/time-based-lag/) | ✅ | ❌ |
| [Retention / data-loss alerting](/metrics/data-loss-prevention/) | ✅ | ❌ |
| Prometheus / Datadog / OTLP export | ✅ | ❌ |
| Alerting on lag trends | ✅ (via your stack) | ❌ |
| [MCP endpoint for AI agents](/ai/mcp/) | ✅ | ❌ |

## When to use which

- **Use AKHQ** when a human needs to *look inside* the cluster — read messages, debug a
  payload, reset a group's offsets, inspect a schema.
- **Use Klag** when you need lag as **metrics**: continuous history, dashboards, and
  alerts that fire before anyone opens a UI. AKHQ's lag view is point-in-time and
  read-through-the-UI; it isn't a Prometheus/Datadog metrics source.

These are complementary. A common setup is AKHQ for interactive exploration **and** Klag
feeding lag metrics into Prometheus/Grafana for continuous alerting.

> Spotted something out of date? Open an issue or PR against
> [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
