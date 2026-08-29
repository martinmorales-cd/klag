---
title: Klag pricing
description: Klag is free and open source under Apache-2.0. There are no tiers, quotas, seats, or paid features.
url: https://klag.dev/pricing.md
---

# Klag pricing

**Klag is free.** It is open-source software licensed under Apache-2.0 and you run it
yourself. There is no hosted service, no account, no API key, no trial, and no paid tier.

| Item | Cost |
| --- | --- |
| Klag itself | $0 — Apache-2.0, source at https://github.com/themoah/klag |
| Container image (`themoah/klag`) | $0 |
| Helm chart | $0 |
| MCP endpoint (`/mcp` on your instance) | $0, opt-in via `MCP_ENABLED=true` |
| Documentation MCP server (`https://klag.dev/mcp`) | $0, no authentication |
| Support | Community, via GitHub issues at https://github.com/themoah/klag/issues |

## What is not free

Klag runs on your infrastructure and writes to your observability backend, so the only
costs are the ones you already control:

- **Compute.** One small container. The GraalVM native image starts in ~70-100 ms and
  holds ~44 MB RSS; the JVM build starts in ~470-520 ms and holds ~119 MB. A single
  replica covers a cluster.
- **Your metrics backend.** Klag adds time series to Prometheus, Datadog, or whatever
  OTLP endpoint you point it at, and those vendors bill per series or per host. Cardinality
  is controllable: `METRICS_GROUP_FILTER` / `METRICS_GROUP_EXCLUDE` limit which consumer
  groups are collected, and `CONSUMER_MEMBER_LABELS_ENABLED=false` drops the per-consumer
  labels on per-partition series.
- **Kafka broker load.** Klag issues a bounded number of AdminClient requests per cycle —
  request volume stays independent of topic count. `KAFKA_CHUNK_COUNT` and
  `KAFKA_CHUNK_DELAY_MS` spread that load out further on large clusters.

## No limits

There are no rate limits, no seat counts, no cluster-size caps, and no feature gates. Every
metric, the Helm chart, the Grafana dashboard, and the MCP endpoint are in the same
Apache-2.0 distribution.

Full documentation: https://klag.dev — machine-readable at https://klag.dev/llms.txt
