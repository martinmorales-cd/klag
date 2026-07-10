---
title: Klag vs other Kafka tools
description: How Klag compares to Burrow, KMinion, AKHQ, Confluent Control Center, Redpanda Console, Grafana, and Cruise Control for Kafka consumer lag monitoring.
---

"Kafka monitoring" covers several different jobs, and the popular tools each solve a
different one. Klag does exactly one: it **exports consumer-lag metrics** to your
observability stack. This page orients you across the landscape, then links to a
focused comparison for each tool.

## Which tool solves which problem

| Tool | Category | What it's for |
|---|---|---|
| **Klag** | Lag metrics exporter | Continuously export consumer lag, velocity, time-lag, retention risk, and group state to Prometheus / Datadog / OTLP. |
| [kafka-lag-exporter](/getting-started/migrating-from-kafka-lag-exporter/) | Archived lag exporter | Former consumer-lag exporter; use the migration guide to map its names, labels, and units to Klag. |
| [Burrow](/comparisons/klag-vs-burrow/) | Lag monitor + notifier | Evaluate lag against status rules and send email/HTTP notifications. |
| [KMinion](/comparisons/klag-vs-kminion/) | Prometheus exporter | Export consumer-group **and** broker/topic/log-dir metrics to Prometheus. |
| [AKHQ](/comparisons/klag-vs-akhq/) | Web UI / console | Browse topics, messages, schemas, and groups interactively. |
| [Confluent Control Center](/comparisons/klag-vs-confluent-control-center/) | Platform UI | Full Confluent Platform management, monitoring, and governance UI. |
| [Redpanda Console](/comparisons/klag-vs-redpanda-console/) | Web UI / console | Browse topics/messages/groups (Kafka and Redpanda). |
| [Grafana](/comparisons/klag-vs-grafana/) | Visualization | Dashboards and alerts on metrics — including Klag's. |
| [Cruise Control](/comparisons/klag-vs-cruise-control/) | Rebalancer | Rebalance partitions and heal the cluster. |

The consoles (AKHQ, Control Center, Redpanda Console) show lag in a UI but aren't
metric exporters or alerting sources. Grafana and Cruise Control do jobs Klag doesn't
touch — you typically run **Klag *with* them**, not instead of them. The closest
head-to-head comparisons are Burrow and KMinion, both dedicated lag/exporter tools.

## At a glance: the lag-focused tools

| | **Klag** | **Burrow** | **KMinion** |
|---|---|---|---|
| Runtime | Java / Vert.x / GraalVM | Go | Go |
| Primary output | Prometheus, Datadog, OTLP | HTTP API + notifiers | Prometheus |
| License | Apache 2.0 | Apache 2.0 | MIT |

## Feature comparison

| Feature | Klag | Burrow | KMinion |
|---|:---:|:---:|:---:|
| Lag in messages | ✅ | ✅ | ✅ |
| Consumer group state | ✅ | ✅ | ✅ |
| Lag velocity (growing/shrinking) | ✅ | ⚠️ status only | ❌ |
| AI agent endpoint (MCP) | ✅ | ❌ | ❌ |
| Time-based lag (seconds/minutes) | ✅ | ❌ | ❌ |
| Time-to-catch-up estimate | ✅ | ❌ | ❌ |
| Hot partition detection | ✅ | ❌ | ❌ |
| Data loss / retention alerting | ✅ | ❌ | ❌ |
| Commit staleness (stuck-consumer signal) | ✅ | ❌ | ❌ |
| Lag status evaluation rules | ❌ | ✅ | ❌ |
| Built-in notifiers (email/Slack/HTTP) | ❌ | ✅ | ❌ |
| Broker / topic / log-dir metrics | ❌ | ❌ | ✅ |
| Prometheus native | ✅ | ⚠️ separate exporter | ✅ |
| Datadog / OTLP native | ✅ | ❌ | ❌ |
| Read-only ACLs (DESCRIBE only) | ✅ | ✅ | ⚠️ writes for e2e |
| GraalVM native image (~44 MB RSS) | ✅ | ❌ | ❌ |
| Helm chart | ✅ | community | community |

## What Klag deliberately doesn't do

Klag is a metrics exporter, so it has **no web UI**, no message/topic browser, no
partition rebalancing, and no built-in alert-rules engine or notifiers. Those are the
jobs of the consoles, Cruise Control, and your alerting stack respectively. Klag emits
the raw signal and expects you to alert on it in Prometheus, Grafana, or Datadog.

Klag's Kafka scope is consumer progress plus partition offsets, under-replicated
partitions, and topic partition counts. It does not export broker health, topic
configuration, or log-directory metrics.

> Spotted something out of date? These tools all evolve. Open an issue or PR against
> [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
