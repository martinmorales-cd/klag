---
title: Troubleshooting
description: Diagnose missing metrics, Kafka readiness and ACL failures, filtering, MCP responses, and Prometheus ServiceMonitor discovery.
---

Monitoring Kafka consumer progress is essential in production. Start with Klag's logs,
`/healthz`, `/readyz`, and the reporter-specific endpoint or backend.

## `/metrics` returns 404

**Likely cause:** The bare application defaults `METRICS_REPORTER` to `none`, or you
selected the `datadog` or `otlp` push reporter. Klag registers `/metrics` only for
Prometheus.

**Fix:** Set `METRICS_REPORTER=prometheus` and restart Klag. The Helm chart already
defaults `metrics.reporter` to `prometheus`. See
[Prometheus](/integrations/prometheus/) and the
[Configuration Reference](/configuration/reference/#metrics).

## Metrics or the MCP snapshot are empty after startup

**Likely cause:** The first Kafka collection has not completed, Kafka access failed, or
no group remains after filtering. The collector starts one cycle immediately; velocity
and some derived metrics still need more samples.

**Fix:** Check the logs for a completed collection or Kafka errors. Verify
`KAFKA_BOOTSTRAP_SERVERS`, the [required ACLs](/kafka/acl-permissions/), and the
[group filters](/configuration/group-filtering/). Wait for a successful cycle. If the
cluster is large, allow longer than `METRICS_INTERVAL_MS`.

## `/readyz` returns 503

**Likely cause:** Klag's Kafka health check cannot complete `describeCluster`. Common
causes are an unreachable broker, bad TLS/SASL settings, or missing cluster
`DESCRIBE`.

**Fix:** Test network and DNS access from the Klag container, then compare your Kafka
security variables with [Installation](/getting-started/installation/). Grant the
cluster ACL documented in [ACL Permissions](/kafka/acl-permissions/). `/healthz` can
still return 200 because it only checks that the process is running.

## Logs show Kafka authorization errors

**Likely cause:** The principal lacks `DESCRIBE` on the cluster, a consumer group, or a
topic. Group filtering does not remove the need for cluster `DESCRIBE`.

**Fix:** Grant the read-only permissions in
[ACL Permissions](/kafka/acl-permissions/). If you grant prefixed group or topic
access, align those prefixes with `METRICS_GROUP_FILTER`.

## No consumer groups appear

**Likely cause:** `METRICS_GROUP_FILTER` matches none of the group IDs, or
`METRICS_GROUP_EXCLUDE` removes every included group.

**Fix:** Temporarily use:

```bash
METRICS_GROUP_FILTER=*
METRICS_GROUP_EXCLUDE=
```

Then add patterns back one at a time. Excludes run after includes. See
[Group Filtering](/configuration/group-filtering/).

## Velocity or time-lag metrics are missing at first

**Likely cause:** Lag velocity needs three collection samples. The time-lag fallback
needs at least two poll intervals when Kafka log timestamps cannot support primary
interpolation. Time-to-close also requires shrinking lag and at least
`TIME_LAG_MIN_MESSAGES` messages.

**Fix:** Wait for the required samples. Shorten `METRICS_INTERVAL_MS` only if Kafka can
handle the added polling load. See [Lag Velocity](/metrics/lag-velocity/) and
[Time-Based Lag](/metrics/time-based-lag/).

## MCP 401, 405, or empty snapshot

**Likely cause:**

- `401`: `MCP_AUTH_TOKEN` is set and the Bearer token is missing or wrong.
- `405`: The client sent `GET`; Klag accepts JSON-RPC 2.0 over `POST`.
- Snapshot not ready: metrics collection is disabled, the first cycle has not
  succeeded, or filters leave no groups to collect.

**Fix:** Send `Authorization: Bearer <token>`, use a Streamable HTTP MCP client that
posts JSON-RPC requests, and select a reporter with `METRICS_REPORTER`. Then resolve
any Kafka or filtering problem reported in the logs. See [MCP Endpoint](/ai/mcp/).

## Prometheus does not discover the ServiceMonitor

**Likely cause:** `serviceMonitor.enabled` is false, the reporter is not Prometheus, or
the Prometheus Operator's ServiceMonitor selector does not match the resource labels.
The chart rejects `serviceMonitor.enabled=true` with a non-Prometheus reporter.

**Fix:** Install or upgrade with:

```bash
helm upgrade --install klag klag/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set metrics.reporter="prometheus" \
  --set serviceMonitor.enabled=true
```

If your Prometheus resource selects a label such as `release: monitoring`, add
`--set serviceMonitor.labels.release=monitoring`. Confirm that the Prometheus
namespace and object selectors permit the ServiceMonitor and that its endpoint selects
the Klag Service. See [Kubernetes deployment](/deployment/kubernetes/#prometheus-servicemonitor).
