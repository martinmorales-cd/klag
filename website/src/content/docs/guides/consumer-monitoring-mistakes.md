---
title: Common Kafka consumer monitoring mistakes
description: Six recurring mistakes teams make when monitoring Kafka consumers — alerting on absolute lag, ignoring velocity and group state, no retention headroom, per-pod blind spots — and how to avoid them.
---

Kafka consumer monitoring tends to fail the same ways. Here are the recurring mistakes
and what to do instead. Each maps to a concrete signal you can put on a dashboard.

## 1. Alerting on absolute lag only

A fixed threshold ("page if lag > 100,000") produces false alarms during normal traffic
spikes and stays silent when a slow leak is building under the threshold. Absolute lag
has no sense of direction.

**Instead:** alert on [lag velocity](/metrics/lag-velocity/) — sustained positive
velocity means the group is losing ground regardless of the current number. Combine a
generous absolute ceiling with a velocity condition.

## 2. Ignoring whether lag is growing or shrinking

The same lag value is benign if it's draining and an incident if it's climbing. Teams
that chart only the current value can't tell the two apart.

**Instead:** always chart the trend, and prefer [time-based lag](/metrics/time-based-lag/)
(lag in minutes + time-to-catch-up) over raw counts — it answers "how long behind, and
closing or not?"

## 3. Not monitoring consumer-group state

A group can go `Empty` (all consumers gone) or thrash in `Rebalancing`/`Dead` while its
lag looks static. If you only watch lag, a group that died quietly won't page you until
the backlog is huge.

**Instead:** alert on `klag_consumer_group_state`. `Empty` while a topic is still
producing, or frequent `Rebalancing`, are early warnings. Klag's
[MCP `diagnose`](/ai/mcp/) tool flags rebalance storms / flapping from state-change
history.

## 4. No retention headroom alerting

Consumer lag and topic retention are usually watched separately, so nobody notices when
lag is about to exceed retention and messages start being deleted unread. By the time
lag "looks bad," data may already be gone.

**Instead:** alert on [retention percent](/metrics/data-loss-prevention/)
(`klag_consumer_lag_retention_percent`). It tells you how much of the retention window
the lag has eaten — alert at 70–80%, well before the 100% data-loss cliff.

## 5. Per-pod / per-partition blind spots

Aggregating lag to the group level hides a single wedged consumer instance or one hot
partition. The group total looks fine; one pod is stuck.

**Instead:** keep per-partition series and the per-consumer member labels
(`member_host` / `consumer_id` / `client_id`, on by default) so you can trace lag to the
owning instance. Watch [hot-partition](/metrics/hot-partitions/) outliers, not just sums.
See [migrating from kafka-lag-exporter](/getting-started/migrating-from-kafka-lag-exporter/)
for the member-label details.

## 6. Assuming low lag means healthy

A crashed or wedged consumer that stops committing shows **flat, low lag** when no new
messages arrive — indistinguishable from "caught up." Lag alone can't see it.

**Instead:** watch commit staleness — see
[How to detect stuck consumers automatically](/guides/detect-stuck-consumers/).

## The short version

| Mistake | Better signal |
|---|---|
| Absolute-lag threshold only | Lag velocity + generous ceiling |
| Charting current value only | Time-based lag + trend |
| Ignoring group state | `klag_consumer_group_state`, rebalance flags |
| No retention headroom | Retention percent alert |
| Group-level aggregation only | Per-partition + member labels + hot partitions |
| "Low lag = healthy" | Commit staleness |

## Next

- [Why monitoring the lag value alone isn't enough](/guides/why-lag-value-is-not-enough/)
- [How to detect stuck consumers automatically](/guides/detect-stuck-consumers/)
