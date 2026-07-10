---
title: MCP Endpoint
description: Klag's opt-in, read-only MCP endpoint lets AI agents query consumer lag, find lagging groups, and run composite diagnose checks, served from an in-memory snapshot.
---

Klag exposes an optional **MCP** (Model Context Protocol) endpoint so AI agents (SRE
copilots, dev assistants) can query consumer-lag state in natural workflows.

It is **opt-in, read-only, and zero-impact when off**. The endpoint serves an in-memory
snapshot the metrics collector publishes after each cycle; it never queries Kafka or
touches the collection flow.

## Enable

| Variable | Default | Description |
|---|---|---|
| `MCP_ENABLED` | `false` | Expose the `/mcp` endpoint. |
| `MCP_AUTH_TOKEN` | _(empty)_ | When set, requires `Authorization: Bearer <token>`. Empty = open (logged warning). |
| `MCP_PATH` | `/mcp` | HTTP path of the endpoint. |

MCP requires `METRICS_REPORTER` to be set. The snapshot is only populated when metrics
collection runs.

## Transport

Streamable HTTP, **JSON-RPC 2.0 over POST**. A `GET` returns `405`.

## Tools

| Tool | Purpose |
|---|---|
| `list_consumer_groups` | List groups, each with its `overallTrend`. |
| `get_consumer_group_lag` | Lag detail for a group, plus `trends`, `overallTrend`, and `recentTransitions`. |
| `find_lagging_groups` | Groups currently lagging, with `overallTrend`. |
| `diagnose` | Composite severity assessment; flags frequent state changes (rebalance storm / flapping). |

## Trends and state history

Each group snapshot carries a **basic lag trend** (`growing` / `shrinking` / `stable`,
per-topic plus an `overallTrend` rollup) derived from
[lag velocity](/metrics/lag-velocity/) via `LAG_TREND_DEADBAND_MSG_PER_SEC`, and a
rolling **state-change history** (last 10 `from→to` transitions). `diagnose` uses the
transition history to flag rebalance storms and flapping groups.

## Connect from an AI client

Klag speaks standard **Streamable HTTP MCP** (JSON-RPC 2.0 over POST). Any client that
supports remote/HTTP MCP servers can connect — there's no Klag-specific SDK. You need
one thing: the endpoint URL, `http://<host>:8888/mcp` by default (`HTTP_PORT` +
`MCP_PATH`). If `MCP_AUTH_TOKEN` is set, add an `Authorization: Bearer <token>` header.
Klag implements MCP protocol version `2025-11-25`.

Use `https://` and a token for anything outside localhost.

:::caution
The snippets below use `Bearer <token>` placeholders. Project-level files like `.mcp.json`
and `.cursor/mcp.json` are often committed to source control — don't put a real token
there. Prefer an untracked global config (`~/.cursor/mcp.json`), your client's
environment-variable/secret interpolation, or a git-ignored file.
:::

### Claude Code

```bash
claude mcp add --transport http klag https://klag.example.com/mcp \
  --header "Authorization: Bearer <token>"
```

Or add it to a project `.mcp.json`:

```json
{
  "mcpServers": {
    "klag": {
      "type": "http",
      "url": "https://klag.example.com/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

### Cursor

Add to `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` (global):

```json
{
  "mcpServers": {
    "klag": {
      "url": "https://klag.example.com/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

### Codex

Add to `~/.codex/config.toml`:

```toml
[mcp_servers.klag]
url = "https://klag.example.com/mcp"
http_headers = { Authorization = "Bearer <token>" }
```

Older Codex builds only spoke stdio and needed the `mcp-remote` bridge
(`command = "npx"`, `args = ["mcp-remote", "https://klag.example.com/mcp"]`). Check your
Codex version's MCP docs if the `url` form isn't recognized.

### Kilo Code

Open the MCP settings (`mcp_settings.json`) and add:

```json
{
  "mcpServers": {
    "klag": {
      "type": "streamable-http",
      "url": "https://klag.example.com/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

:::note
MCP clients evolve quickly and config field names change between versions. If a snippet
above doesn't match your client, consult its current MCP docs — Klag only requires a
Streamable-HTTP JSON-RPC POST endpoint, so any correct remote-MCP config will work.
:::

Once connected, ask the agent to `list_consumer_groups`, `find_lagging_groups`, or
`diagnose` a specific group.

## Design

The MCP layer reads from a `SnapshotStore` populated by the metrics collector, never
from direct Kafka calls. See the design doc:
[`docs/superpowers/specs/2026-06-01-mcp-support-design.md`](https://github.com/themoah/klag/blob/main/docs/superpowers/specs/2026-06-01-mcp-support-design.md).
