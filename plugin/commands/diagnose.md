---
description: Triage Kafka consumer lag using a running Klag — rank what is falling behind, diagnose a group, explain the likely cause
argument-hint: "[consumer-group] (optional — otherwise triage the whole cluster)"
allowed-tools: [Bash, Read, WebFetch]
---

Triage consumer lag with the user's running Klag. Group: `$ARGUMENTS` (empty = whole cluster).

Prefer the MCP tools. If the `klag` MCP server is not registered in this session, say so, fall
back to scraping `/metrics`, and point at `/klag:connect`.

## Path A — MCP (preferred)

1. `find_lagging_groups` (`sortBy`: `lag` | `velocity` | `retention`, `limit`) for the cluster
   view, or go straight to the named group.
2. `diagnose <group>` — returns `severity` (`OK` | `INFO` | `WARNING` | `CRITICAL`), a `summary`,
   and `findings[]` of `{severity, title, detail}`.
3. `get_consumer_group_lag <group>` when you need per-partition detail. Response keys:
   `partitions[]` (`lag`, `committedOffset`, `logEndOffset`, `logStartOffset`), `velocity`,
   `lagMs`, `timeToClose`, `retentionRisk`, `trends`, `overallTrend`, `recentTransitions`,
   `commitStalenessSeconds`. Read the keys off the response rather than assuming — this list is
   a convenience, the server is the authority.

Check `snapshotAgeMs` in the response. Anything much older than `METRICS_INTERVAL_MS` means you
are reading a stale picture — say so rather than diagnosing confidently off it.

## Path B — no MCP

```bash
curl -s <klag>/metrics | grep -E '^klag_consumer_lag(_sum)?\{|^klag_consumer_commit_staleness_seconds|^klag_consumer_lag_retention_percent|^klag_consumer_group_state|^klag_consumer_lag_velocity'
```

Same reasoning, less structure. Note in your answer that MCP would give a better one.

## Reading the result

Map findings to causes, worst first:

| Signal | Reading |
|---|---|
| state `dead` | group gone — consumers crashed or never restarted. Look at the app, not Kafka. |
| state `empty` + lag | nobody consuming; work is piling up. |
| `commitStalenessSeconds` high while lag > 0 | **stuck consumer**: alive but not advancing — poison message, wedged handler, or a blocked downstream. Lag alone misses this. |
| many `recentTransitions` | rebalance storm / flapping: `max.poll.interval.ms` too low, slow processing, or unstable pods. |
| `retention_percent` climbing toward 100 | **data loss risk** — messages will expire unread. This outranks raw lag size. |
| lag growing, velocity positive | producers outpace consumers: scale out, or the partition count caps parallelism. |
| hot partition | skewed keys, not a consumer problem — repartition or change the key. |
| under-replicated partitions | broker-side fault tolerance loss, unrelated to the consumer. |

Freshness caveats worth stating when they matter: `commitStalenessSeconds` is *inferred* (Kafka
exposes no commit timestamp — it measures time since Klag last saw the offset move, and resets on
Klag restart), and it is only reported while lag > 0.

Background on any of these: `https://klag.dev/guides/detect-stuck-consumers/`,
`https://klag.dev/metrics/data-loss-prevention/`, `https://klag.dev/guides/troubleshooting/`.

## Output

Lead with the verdict — healthy, or the single worst problem and which group has it. Then the
evidence (numbers, not adjectives), then the concrete next step. Keep it short. Do not change
anything in the user's cluster from this command.
