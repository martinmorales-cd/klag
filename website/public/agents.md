# Klag — start here (for agents)

Klag is an open-source **Kafka consumer lag exporter**. It polls Kafka read-only and exports
consumer lag and derived signals — lag velocity, time-based lag, hot partitions, commit
staleness, retention risk, consumer-group state, under-replicated partitions — to Prometheus,
Datadog, or OTLP. Apache-2.0, self-hosted, free.

Canonical site: <https://klag.dev> · Source: <https://github.com/themoah/klag>

## When to use it

- Monitor or alert on Kafka consumer lag.
- Explain *why* a consumer group is behind, not just that it is.
- Detect a stuck consumer: lag held while the committed offset stops advancing.
- Replace the unmaintained `kafka-lag-exporter`, keeping metric names.

Not a Kafka UI, topic browser, or cluster balancer.

## Machine-readable entry points

| Resource | URL |
| --- | --- |
| Docs index | <https://klag.dev/llms.txt> |
| Full docs corpus | <https://klag.dev/llms-full.txt> |
| Markdown twin of any page | append `.md` to its URL |
| Documentation MCP server (read-only, no auth) | `https://klag.dev/mcp` |
| Agent skills index | <https://klag.dev/.well-known/agent-skills/index.json> |
| Resource catalog (ARD) | <https://klag.dev/.well-known/ai-catalog.json> |
| OpenAPI 3.1 for a Klag instance | <https://klag.dev/openapi.json> |
| Pricing (free) | <https://klag.dev/pricing.md> |

## Run it

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092 \
           -e METRICS_REPORTER=prometheus \
           -p 8888:8888 \
           themoah/klag:latest
```

Metrics land at `http://localhost:8888/metrics`; readiness at `/readyz`. For Kubernetes use
the Helm chart: <https://klag.dev/deployment/kubernetes/>.

## Two MCP servers — do not confuse them

- `https://klag.dev/mcp` — hosted, answers questions **about Klag** (docs, config, metrics).
- `/mcp` on **your** Klag instance — opt-in (`MCP_ENABLED=true`), answers questions about
  **your** Kafka consumer groups.
