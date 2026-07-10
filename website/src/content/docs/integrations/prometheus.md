---
title: Prometheus
description: Scrape Klag metrics with Prometheus via the /metrics endpoint.
---

Prometheus needs no push gateway or backend credentials. The Helm chart selects it by
default; the bare application defaults `METRICS_REPORTER` to `none`, so Docker and
binary deployments must set `METRICS_REPORTER=prometheus`.

## Enable

Replace the example broker address with one that resolves and is reachable from inside
the Klag container. Use Docker network or service DNS for Kafka in another container,
or `host.docker.internal:9092` for Kafka exposed by the host.

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092 \
           -e METRICS_REPORTER=prometheus \
           -p 8888:8888 \
           themoah/klag:latest
```

Metrics are then served at `http://localhost:8888/metrics` in Prometheus text format.

## Scrape config

```yaml
scrape_configs:
  - job_name: klag
    metrics_path: /metrics
    static_configs:
      - targets: ['klag:8888']
```

On Kubernetes, the [Helm chart](/deployment/kubernetes/) can render a `ServiceMonitor`
for the Prometheus Operator. See the chart's `values.yaml`.

If `/metrics` returns `404` or Prometheus cannot discover the ServiceMonitor, see
[Troubleshooting](/guides/troubleshooting/).

## What you get

All [metrics](/metrics/overview/) are exposed with `consumer_group`, `topic`, and
`partition` labels where applicable. Import the
[Grafana dashboard](/integrations/grafana-dashboard/) for ready-made panels.
