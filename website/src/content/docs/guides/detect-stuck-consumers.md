---
title: How to detect stuck consumers automatically
description: A stuck Kafka consumer has pending work but a frozen committed offset — and raw lag often misses it. Learn to detect stuck consumers with commit staleness and Klag's diagnose tool.
---

A **stuck consumer** is one that still has work to do but has stopped making progress —
its committed offset is frozen while messages wait. The dangerous part is that raw lag
often fails to catch it. This guide explains why, and how to detect stuck consumers
automatically.

## Stuck vs merely lagging

- **Lagging:** the consumer is behind but still committing — the offset advances, lag
  rises and falls with load. It may well catch up on its own.
- **Stuck:** the committed offset has stopped advancing. The consumer is wedged — a
  poison message, a deadlock, a hung downstream call, an OOM loop, a paused partition.

The two look different in one specific way: for a stuck consumer the **committed offset
is flat over time**, even though lag is greater than zero.

## Why lag alone misses it

Lag = log-end offset − committed offset. Consider a wedged consumer:

- If producers keep writing, lag climbs — but so does lag for any slow consumer, so a
  simple threshold can't distinguish "wedged" from "busy."
- If producers **stop or slow**, the log-end offset stops advancing too. Now lag is
  flat and possibly small — and a wedged consumer looks identical to a caught-up one.

That second case is the trap: **flat, low lag can mean "healthy" or "stuck," and lag
can't tell you which.**

## The signal that catches it: commit staleness

The distinguishing fact is *time since the committed offset last advanced*. A caught-up
consumer has zero pending work; a stuck one has pending work and a frozen offset.

Klag's [commit-staleness metric](/metrics/overview/)
(`klag_consumer_commit_staleness_seconds`) measures exactly this: seconds since a
group+topic's committed offset last moved. Crucially, it's **only reported while lag >
0** — a consumer that's genuinely idle (no lag, no commits) is not stuck, so it's not
flagged. Rising commit staleness with non-zero lag is the stuck-consumer fingerprint.

> Note: Kafka exposes no commit timestamp, so staleness is *inferred* — Klag timestamps
> when it observes the committed offset change, and the clock resets on Klag restart. It
> measures time since Klag last *saw* a commit, not absolute commit time.

## Alerting on it

A simple, robust rule:

```promql
# Consumer with pending work whose offset hasn't advanced in 5 minutes
klag_consumer_commit_staleness_seconds > 300
```

Because the metric only exists while lag > 0, this fires only for consumers that have
something to do and aren't doing it. Tune the threshold to your commit cadence — set it
comfortably above your normal auto-commit interval and processing time.

## Automatic detection with the diagnose tool

Klag's [MCP `diagnose` tool](/ai/mcp/) applies this automatically. For a given group it
combines state, lag trend, retention risk, and hot partitions into a severity
assessment, and **flags stuck consumers** (lag > 0 with a frozen committed offset) using
a fixed 300-second threshold — so an SRE agent can ask "diagnose group X" and get the
stuck signal without hand-writing PromQL. It also raises a state-churn warning after
three retained transitions. That history is not time-windowed, so inspect
`recentTransitions` before treating the warning as a rebalance storm or flapping.

## Checklist

- Chart the **committed offset** over time — flat while lag > 0 is the tell.
- Alert on `klag_consumer_commit_staleness_seconds > <your threshold>`.
- Watch consumer-group **state** for `Empty` / repeated `Rebalancing` alongside it.
- For ad-hoc triage, use the MCP [`diagnose`](/ai/mcp/) tool.

## Next

- [Common Kafka consumer monitoring mistakes](/guides/consumer-monitoring-mistakes/)
- [Why monitoring the lag value alone isn't enough](/guides/why-lag-value-is-not-enough/)
