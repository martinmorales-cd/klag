---
title: Datadog
description: Ship Klag metrics directly to Datadog using the Datadog Micrometer registry.
---

Klag can submit metrics straight to Datadog via the Micrometer Datadog registry, with no
Prometheus scrape required.

## Enable

Set the reporter, Kafka collection interval, required API key, and site. The broker
address is a replaceable example and must resolve and be reachable from inside the Klag
container; use Docker network or service DNS for Kafka in another container, or
`host.docker.internal:9092` for Kafka exposed by the host:

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092 \
           -e METRICS_REPORTER=datadog \
           -e METRICS_INTERVAL_MS=30000 \
           -e DD_API_KEY='<your-api-key>' \
           -e DD_SITE=datadoghq.com \
           themoah/klag:latest
```

`DD_API_KEY` is required for metric submission. `DD_APP_KEY` is optional and is used
for metadata operations; add `-e DD_APP_KEY='<your-application-key>'` when you want
them. `DD_SITE` defaults to `datadoghq.com`. Use your Datadog site, such as
`datadoghq.eu`, when needed.

`DD_API_KEY`, `DD_APP_KEY`, and `DD_SITE` are environment-only. Klag's
`MicrometerConfig` reads them directly from the process environment; classpath and
external properties files do not configure these credentials.

## Helm

Store credentials in an existing Secret so they do not appear in Helm release values:

```bash
kubectl create secret generic klag-datadog \
  --from-literal=api-key='<your-api-key>' \
  --from-literal=app-key='<your-application-key>'

helm install klag klag/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set metrics.reporter="datadog" \
  --set metrics.intervalMs=30000 \
  --set metrics.datadog.existingSecret="klag-datadog" \
  --set metrics.datadog.site="datadoghq.com"
```

The default Secret keys are `api-key` and `app-key`. Change
`metrics.datadog.secretKeys.apiKey` or `metrics.datadog.secretKeys.appKey` if your
Secret uses different keys. See the
[chart README](https://github.com/themoah/klag/blob/main/charts/klag/README.md) for
all Datadog values.

## Notes

- `METRICS_INTERVAL_MS` controls how often Klag polls Kafka and refreshes its meters.
  It does not configure the Datadog registry's submission step.
- All [metrics](/metrics/overview/) carry `consumer_group`, `topic`, and `partition`
  tags where applicable, so you can build Datadog monitors per group or topic.
