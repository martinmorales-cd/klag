---
title: Klag vs Redpanda Console
description: Redpanda Console is a web UI for browsing Kafka/Redpanda topics, messages, and groups; Klag is a headless consumer-lag metrics exporter. Different jobs, often run together.
---

**Klag** and
[Redpanda Console](https://github.com/redpanda-data/console) solve different problems.
Redpanda Console is an interactive **web UI** for Kafka and Redpanda clusters; Klag is a
**headless metrics exporter** for consumer lag. Despite the name, Redpanda Console works
with any Kafka-compatible cluster, not just Redpanda — and so does Klag.

## What each tool is

- **Klag** is a lag metrics exporter. No UI; it publishes metrics to Prometheus,
  Datadog, or OTLP.
- **Redpanda Console** is a web UI for exploring topics and messages, viewing consumer
  groups and their lag, inspecting schemas, and browsing cluster configuration. It
  presents lag interactively in the browser.

## At a glance

| | **Klag** | **Redpanda Console** |
|---|---|---|
| Category | Lag metrics exporter | Web UI / console |
| Interface | Metrics endpoint | Interactive browser UI |
| Message browsing | ❌ | ✅ |
| Time-series lag & alerting | ✅ | ❌ (point-in-time view) |
| Runtime | Java / Vert.x / GraalVM | Go |
| License | Apache 2.0 | BSL (enterprise features separately licensed) |

## Feature comparison

| Feature | Klag | Redpanda Console |
|---|:---:|:---:|
| Browse topics & messages | ❌ | ✅ |
| Consumer-group & lag views | ❌ | ✅ |
| Schema & config inspection | ❌ | ✅ |
| Current lag in a UI | ❌ | ✅ |
| Lag as time-series metrics | ✅ | ❌ |
| Lag velocity, [time-based lag](/metrics/time-based-lag/) | ✅ | ❌ |
| [Retention / data-loss alerting](/metrics/data-loss-prevention/) | ✅ | ❌ |
| Prometheus / Datadog / OTLP export | ✅ | ❌ |
| [MCP endpoint for AI agents](/ai/mcp/) | ✅ | ❌ |

## When to use which

- **Use Redpanda Console** when a human needs to explore the cluster interactively —
  read messages, inspect a group, check a schema — through a polished UI.
- **Use Klag** when you need lag as **metrics**: continuous history, dashboards, and
  alerts that fire automatically. Redpanda Console shows lag point-in-time in the UI; it
  isn't a Prometheus/Datadog metrics source.

They're complementary: Redpanda Console for interactive exploration, Klag feeding lag
metrics into Prometheus/Grafana for continuous alerting.

> Spotted something out of date? Open an issue or PR against
> [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
