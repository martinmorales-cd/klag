---
description: Connect a running Klag's MCP endpoint to this AI client so the agent can query consumer lag directly
argument-hint: "[klag-url] (optional, e.g. http://localhost:8888)"
allowed-tools: [Bash, Read, Write, Edit, WebFetch, AskUserQuestion]
---

Wire the user's running Klag into their AI client over MCP. URL hint: `$ARGUMENTS`.

Klag's MCP is read-only and served from an in-memory snapshot — it never queries Kafka on
demand. Tools: `list_consumer_groups`, `get_consumer_group_lag`, `find_lagging_groups`,
`diagnose`.

## 1. Find the endpoint

If `$ARGUMENTS` has a URL, use it. Otherwise look for a running Klag. Export the result as
`KLAG_URL` (and `NS` for the namespace) so the commands below stay quoted and copy-pasteable:

```bash
docker ps --filter ancestor=themoah/klag --format '{{.Names}} {{.Ports}}'
kubectl get deploy -A -l app.kubernetes.io/name=klag 2>/dev/null
```

For a cluster install, start a port-forward (confirm first) and use `http://localhost:18888`:

```bash
kubectl -n "$NS" port-forward svc/klag 18888:8888
```

A port-forward dies with the shell — tell the user the registration points at a tunnel they
must keep open, and that a real setup should expose Klag through a Service/Ingress and register
that URL instead.

## 2. Check the prerequisites

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$KLAG_URL/readyz"
# Drop the Authorization line when MCP_AUTH_TOKEN is unset — do not send the literal placeholder.
curl -s -X POST "$KLAG_URL/mcp" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $KLAG_MCP_TOKEN" \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

- `404` on `/mcp` → `MCP_ENABLED` is not `true`. Offer to enable it (`mcp.enabled=true` in the
  chart, `-e MCP_ENABLED=true` for docker) and redeploy.
- `401` → `MCP_AUTH_TOKEN` is set; get the token from the user or read it from the Secret with
  their confirmation. Never print it back.
- `405` → that was a GET; MCP here is POST only.
- Empty snapshot → the collector has nothing to publish. Check in this order: `METRICS_REPORTER`
  is set (default `none` means no collection at all), `/metrics` actually has
  `klag_consumer_lag` series, `METRICS_GROUP_FILTER` / `METRICS_GROUP_EXCLUDE` do not exclude
  every group, and the cluster really does have groups with committed offsets. Only the first
  needs a redeploy.

## 3. Register

Claude Code:

```bash
claude mcp add --transport http klag "$KLAG_URL/mcp" --header "Authorization: Bearer $KLAG_MCP_TOKEN"
```

Omit the header if no token is set. Pass the token through a shell variable rather than typing it
inline — a literal token on the command line lands in shell history and in the process list. Prefer user scope over a project `.mcp.json` when the URL is
a local port-forward, and never commit a real token — `.mcp.json` and `.cursor/mcp.json` are
usually tracked files.

For other clients, print the snippet for whichever the user is on rather than all of them:

- Cursor — `~/.cursor/mcp.json`: `{"mcpServers":{"klag":{"url":"<url>/mcp","headers":{"Authorization":"Bearer <token>"}}}}`
- Codex — `~/.codex/config.toml`: `[mcp_servers.klag]` with `url` and
  `bearer_token_env_var = "KLAG_MCP_TOKEN"` (keeps the token out of the config file; export the
  variable before launching Codex). `http_headers = { Authorization = "Bearer <token>" }` also
  works but persists the token on disk.
- GitHub Copilot (VS Code) — `.vscode/mcp.json` or user MCP config: top-level `servers.klag`
  with `"type":"http"`, `"url":"<url>/mcp"`, `"headers":{"Authorization":"Bearer <token>"}`
- GitHub Copilot CLI — `copilot mcp add --transport http --header "Authorization: Bearer $KLAG_MCP_TOKEN" klag "$KLAG_URL/mcp"`
  or `~/.copilot/mcp-config.json` with `"type":"http"`
- OpenCode — `opencode.json`: `mcp.servers.klag` with `"type":"remote"`, `"url":"<url>/mcp"`,
  `"oauth":false`, `"headers":{"Authorization":"Bearer {env:KLAG_MCP_TOKEN}"}`
- Anything else: Klag needs only a Streamable-HTTP JSON-RPC POST endpoint. Current per-client
  configs live at `https://klag.dev/ai/mcp/`.

## 4. Verify

Call `list_consumer_groups` (through the registered server if it is live in this session,
otherwise with the `curl` above) and report the group count and a couple of names. If the client
needs a restart to pick up the server, say so instead of claiming it works.

Then suggest `/klag:diagnose`.
