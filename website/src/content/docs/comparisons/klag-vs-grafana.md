---
title: Klag vs Grafana
description: Grafana visualizes and alerts on metrics; Klag produces the Kafka consumer-lag metrics. They aren't alternatives — you run Klag as the data source and Grafana as the dashboard.
---

This is the one comparison where the answer is almost always "**both**." Grafana doesn't
produce metrics — it visualizes and alerts on metrics that something else produces. Klag
is one of those producers: it generates Kafka consumer-lag metrics that Grafana then
charts and alerts on.

## What each tool is

- **Klag** is a lag metrics *exporter* (the data source). It reads consumer lag from
  Kafka and exposes it to Prometheus, Datadog, or OTLP.
- **Grafana** is a visualization and alerting *frontend*. It queries data sources
  (Prometheus, Grafana Cloud, Datadog, etc.), renders dashboards, and evaluates alert
  rules. It does not collect Kafka lag itself.

## They're layers, not alternatives

| Layer | Tool |
|---|---|
| Collect lag from Kafka | **Klag** |
| Store the time series | Prometheus / Grafana Cloud / Datadog |
| Visualize & alert | **Grafana** |

Without a data source, Grafana has no Kafka lag to show. Without a frontend, Klag's
metrics still exist but aren't charted. You run them together.

## Using Klag with Grafana

Klag ships a pre-built Grafana dashboard covering lag, velocity, hot partitions,
time-based lag, data-loss prevention, ISR, and JVM panels:

- [Grafana dashboard](/integrations/grafana-dashboard/) — import the ready-made dashboard.
- [OTLP & Grafana Cloud](/integrations/otlp-grafana/) — send Klag metrics to Grafana
  Cloud over OpenTelemetry.
- [Prometheus](/integrations/prometheus/) — scrape Klag and point Grafana at Prometheus.

## When you'd think it's "vs" — and why it isn't

If you've seen "Grafana for Kafka lag," that means Grafana **displaying** lag metrics
some exporter collected. Grafana + the Kafka data source you already run *is* the setup;
Klag is a strong choice for that exporter because it adds velocity, time-based lag,
retention risk, and hot-partition signals on top of raw lag — all of which render
directly in the bundled dashboard.

> Spotted something out of date? Open an issue or PR against
> [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
