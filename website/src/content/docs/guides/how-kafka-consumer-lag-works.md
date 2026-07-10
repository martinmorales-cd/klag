---
title: How Kafka consumer lag actually works
description: A plain-English explanation of Kafka consumer lag — offsets, committed vs log-end offset, per-partition accounting — and what the lag number really measures.
---

Consumer lag is the most-watched Kafka health signal, and also the most
misunderstood. This guide explains what lag actually is, mechanically, so the metrics
make sense.

## Offsets: Kafka's ruler

Every message in a Kafka partition has a monotonically increasing **offset** — 0, 1, 2,
and so on. Offsets are per-partition, not per-topic. Two things move along this ruler:

- **Log-end offset (LEO):** the offset of the next message a producer will write. It's
  the "head" of the partition — how far *production* has reached.
- **Committed offset:** the offset a consumer group has recorded as processed, stored in
  the internal `__consumer_offsets` topic. It's how far *consumption* has reached.

Consumer lag for one partition is simply:

```text
lag = log-end offset − committed offset
```

If the log-end offset is 1,000 (producers have written through offset 999) and the group
has committed offset 950, the group is **50 messages behind** on that partition.

## Lag is per-partition, then aggregated

A consumer group subscribes to topics, and each topic has partitions. Lag is measured
**per partition**, then rolled up:

- Per group + topic: sum of partition lags (total backlog), plus max (the worst
  partition).
- Per group: sum across all its topics.

This matters because a group can look "fine" on total lag while one partition is badly
behind — a [hot partition](/metrics/hot-partitions/) or a stuck assignment. Klag exposes
both the per-partition series and the aggregates so you can drill in. See the
[Metrics Overview](/metrics/overview/) for the exact metric names
(`klag_consumer_lag`, `klag_consumer_lag_sum`, `klag_consumer_lag_max`).

## Committed ≠ processed, exactly

The committed offset reflects what the consumer has *committed*, which depends on the
commit strategy (auto-commit interval, or manual commit after processing). With
auto-commit, a consumer can commit an offset slightly ahead of what it has fully
processed. Lag is therefore a close proxy for "unprocessed messages," not a
cryptographic guarantee. For most alerting this distinction doesn't matter; for
exactly-once reasoning it does.

## Why a raw lag count can mislead

"50,000 messages behind" means nothing without throughput. At 50,000 msg/s it's one
second of backlog; at 50 msg/s it's a growing incident. That's why message-count lag
alone is a weak signal — see
[Why monitoring lag value alone isn't enough](/guides/why-lag-value-is-not-enough/).

## How lag is read from Kafka

A lag exporter like Klag never joins the consumer group. It uses the Kafka **Admin API**
(read-only, `DESCRIBE`) to fetch each group's committed offsets and each partition's
log-end offset, subtracts, and reports. No messages are consumed, no offsets are written.
See [ACL Permissions](/kafka/acl-permissions/) for the exact grants.

## Next

- [Why monitoring the lag value alone isn't enough](/guides/why-lag-value-is-not-enough/)
- [Common Kafka consumer monitoring mistakes](/guides/consumer-monitoring-mistakes/)
- [How to detect stuck consumers automatically](/guides/detect-stuck-consumers/)
