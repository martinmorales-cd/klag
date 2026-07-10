---
title: Data Loss Prevention
description: Klag's retention-percent metric warns you before consumer lag exceeds Kafka retention and messages are permanently lost.
---

The most dangerous kind of lag is the kind that crosses your **retention window**. Once
a consumer falls further behind than Kafka retains, the oldest unread messages are
deleted, gone, unrecoverable. Klag warns you **before** that happens.

## The metric

| Metric | Description |
|---|---|
| `klag.consumer.lag.retention_percent` | Percentage of the retention window consumed by lag, capped at 100% and exported as percentage × 100 for precision. |

The raw gauge is scaled: **raw `8000` means 80%**, and raw `10000` means the
100% retention boundary or possible data loss. It does not prove loss by itself.
Divide by `100` in queries and dashboards when you want a conventional 0–100 percentage.

Klag emits both:

- a topic aggregate (the maximum risk across its partitions), tagged with
  `consumer_group` and `topic`
- per-partition series, additionally tagged with `partition`

The aggregate omits the `partition` label. In PromQL, `{partition=""}` matches a label
that is absent, so use it for one series per group/topic and `{partition!=""}` for
partition detail.

## Formula

```text
retention_window = logEndOffset - logStartOffset

if retention_window <= 0:
    emit no metric
else if committedOffset < logStartOffset:
    calculated_percent = 100
else if lag <= 0:
    calculated_percent = 0
else:
    calculated_percent = (lag / retention_window) * 100

exported_gauge = round(calculated_percent * 100)
```

- **A rising value** means the consumer is eating into its safety margin.
- At `committedOffset == logStartOffset`, the metric reaches **100%** but the earliest
  retained record is still readable.
- At `committedOffset < logStartOffset`, unread records have been deleted and data loss
  has occurred. This case is also capped at raw `10000`, so the metric cannot distinguish
  it from the readable 100% boundary.

## Why offsets, not time

Retention in Kafka is enforced by the broker deleting old segments. Comparing lag to the
actual span of **available** offsets (`logEndOffset − logStartOffset`) measures the real,
current safety margin, more reliable than assuming a fixed time-based retention.

## Alerting

For a topic-level visualization in conventional percent units:

```promql
klag_consumer_lag_retention_percent{partition=""} / 100
```

For per-partition investigation:

```promql
klag_consumer_lag_retention_percent{partition!=""} / 100
```

Alert well below 100% (for example, at 80% or above) to leave time to intervene. Either
compare the raw scaled value:

```promql
klag_consumer_lag_retention_percent{partition=""} >= 8000
```

or normalize first:

```promql
(klag_consumer_lag_retention_percent{partition=""} / 100) >= 80
```

Keeping `partition=""` in topic-level rules avoids evaluating both the aggregate and
each per-partition series. The
[Grafana dashboard](/integrations/grafana-dashboard/) includes retention-risk panels and
an at-risk topics table.
