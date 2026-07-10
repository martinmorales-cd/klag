---
title: Why monitoring the lag value alone isn't enough
description: A single consumer-lag number hides direction, time-to-impact, and data-loss risk. Learn which extra signals — velocity, time-based lag, retention percent — give lag its missing context.
---

Most Kafka monitoring stops at one number: consumer lag in messages. It's necessary but
not sufficient. A lag value on its own can't tell you whether things are getting better
or worse, how bad the impact is, or whether you're about to lose data. This guide covers
the context a raw lag count is missing — and the metrics that supply it.

## Problem 1: a number has no direction

Lag of 50,000 is meaningless without a trend. Is it draining or climbing?

- Static lag that's shrinking → the consumer is catching up; probably fine.
- The same lag climbing → an incident in progress.

**The missing signal is velocity** — the rate of change of lag. Klag's
[lag velocity](/metrics/lag-velocity/) metric (`klag_consumer_lag_velocity`) is positive
when a group is falling behind and negative when catching up. Alerting on velocity
catches problems while lag is still small.

## Problem 2: messages aren't time

"50,000 messages behind" could be one second or one hour of delay depending on
throughput. Business impact is measured in **time**, not message count. Klag's
[time-based lag](/metrics/time-based-lag/) (`klag_consumer_lag_ms`) estimates lag in
milliseconds by mapping the committed offset to a wall-clock timestamp, and
`klag_consumer_lag_time_to_close_seconds` estimates how long until the group catches up.
"12 minutes behind, closing in 3" is far more actionable than "50,000."

## Problem 3: lag hides data-loss risk

Kafka topics have retention. If lag grows faster than the consumer drains it, the
consumer can fall behind the partition's **log-start offset** — the oldest retained
message — and messages are deleted **before they're ever read**. Lag in messages doesn't
tell you how close you are to that cliff.

Klag's [retention-percent metric](/metrics/data-loss-prevention/)
(`klag_consumer_lag_retention_percent`) reports how much of the retention window the lag
has consumed. At 80% you still have headroom; at 100% data loss has already occurred.
This is a fundamentally different alert than "lag is high."

## Problem 4: aggregate lag hides skew

Total lag across a topic can look healthy while one partition is badly behind — a
[hot partition](/metrics/hot-partitions/) or a wedged assignment. Watch per-partition
lag and hot-partition outliers, not just the sum.

## Problem 5: frozen offsets look like low lag

A consumer that has crashed or wedged mid-batch can stop committing entirely. If no new
messages arrive, lag stays flat and low — and looks fine — while nothing is being
processed. See [How to detect stuck consumers automatically](/guides/detect-stuck-consumers/)
for the commit-staleness signal that catches this.

## A better alerting model

| Instead of alerting on… | Also alert on… |
|---|---|
| Absolute lag > N | Lag **velocity** > 0 sustained |
| Absolute lag > N | Lag **in time** (ms / minutes) |
| Absolute lag > N | **Retention percent** approaching 100% |
| Group total lag | **Per-partition** / hot-partition outliers |
| Lag value | **Commit staleness** (stuck consumers) |

Raw lag is the starting point, not the finish line. Layering these signals turns "the
number is high" into "here's what's happening, how bad, and how long you have."

## Next

- [Common Kafka consumer monitoring mistakes](/guides/consumer-monitoring-mistakes/)
- [How to detect stuck consumers automatically](/guides/detect-stuck-consumers/)
