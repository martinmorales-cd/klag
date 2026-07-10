---
title: Klag vs Confluent Control Center
description: Confluent Control Center is the full Confluent Platform management UI; Klag is a lightweight open-source consumer-lag exporter for Prometheus, Datadog, and OTLP. They serve different needs.
---

**Klag** and
[Confluent Control Center](https://docs.confluent.io/platform/current/control-center/index.html)
sit at opposite ends of the tooling spectrum. Control Center is the comprehensive
management and monitoring UI for **Confluent Platform**; Klag is a single-purpose,
open-source **consumer-lag exporter** that works with any Kafka-compatible cluster.

## What each tool is

- **Klag** is an Apache-2.0 lag metrics exporter. It monitors consumer lag and ships
  metrics to Prometheus, Datadog, or OTLP. It runs against any Kafka-compatible cluster.
- **Confluent Control Center (C3)** is Confluent's commercial platform UI, part of
  Confluent Platform. It provides broker/cluster monitoring, consumer-lag views, topic
  and connector management, ksqlDB, schema management, and governance — through a web
  console.

## At a glance

| | **Klag** | **Confluent Control Center** |
|---|---|---|
| Category | Lag metrics exporter | Platform management UI |
| Scope | Consumer lag | Whole Confluent Platform |
| Interface | Metrics endpoint | Web console |
| Licensing | Open source (Apache 2.0) | Commercial (Confluent Platform) |
| Runs against | Any Kafka-compatible cluster | Primarily Confluent Platform |
| Footprint | ~44 MB RSS (native) | Substantial (platform component) |

## Feature comparison

| Feature | Klag | Control Center |
|---|:---:|:---:|
| Consumer-lag metrics | ✅ | ✅ |
| Web UI / console | ❌ | ✅ |
| Broker / cluster management | ❌ | ✅ |
| Connector & ksqlDB management | ❌ | ✅ |
| Lag velocity, [time-based lag](/metrics/time-based-lag/) | ✅ | ⚠️ platform-dependent |
| [Retention / data-loss alerting](/metrics/data-loss-prevention/) | ✅ | ⚠️ platform-dependent |
| Prometheus / Datadog / OTLP export | ✅ | ⚠️ via platform integrations |
| Open source | ✅ | ❌ |
| [MCP endpoint for AI agents](/ai/mcp/) | ✅ | ❌ |

## When to use which

- **Use Control Center** if you run Confluent Platform and want an all-in-one console for
  managing and monitoring the whole stack (brokers, connectors, ksqlDB, governance) with
  Confluent support.
- **Use Klag** if you want a lightweight, open-source, vendor-neutral way to get consumer
  lag into Prometheus/Datadog/OTLP — on any Kafka, including open-source Apache Kafka,
  Strimzi, MSK, or Redpanda — without adopting a platform. Klag's footprint is tiny
  (native image ~44 MB RSS) and it adds signals like velocity, retention risk, and a
  read-only [MCP endpoint](/ai/mcp/) for AI agents.

> Spotted something out of date? Control Center's feature set evolves with Confluent
> Platform. Open an issue or PR against
> [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
