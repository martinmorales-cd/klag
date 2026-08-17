---
description: Install Klag against a Kafka cluster — detects docker/k8s/GitOps, deploys, verifies lag metrics, offers MCP
argument-hint: "[docker|compose|helm|gitops|jar] (optional — otherwise detected)"
allowed-tools: [Bash, Read, Write, Edit, Glob, Grep, WebFetch, AskUserQuestion]
---

Set up Klag for the user. Target hint: `$ARGUMENTS` (may be empty — then detect).

Read `${CLAUDE_PLUGIN_ROOT}/skills/klag/SKILL.md` and
`${CLAUDE_PLUGIN_ROOT}/skills/klag/references/deploy-targets.md` first. Read
`references/kafka-connect.md` once you know the Kafka is not a local plaintext one.

**Confirm before every command that mutates the user's machine or cluster**, showing the exact
command. Detection is read-only and needs no confirmation.

## 1. Detect

Run the detection block from `deploy-targets.md` (docker, kube context, helm, ArgoCD/Flux CRDs,
Strimzi CRDs, Prometheus Operator CRDs, java). Report what you found in two or three lines.

If the kube context matches `prod|production|prd`, say so explicitly and get a separate
confirmation before touching it.

## 2. Choose the target

Use `AskUserQuestion`, ordering options by what you detected. If `$ARGUMENTS` names a target, skip
this step. Options:

- **Demo POC** — the repo's `docker-compose.yaml`: Kafka + Klag + a producer + three misbehaving
  consumers, so real lag appears in about a minute. Best when the user has no Kafka handy or just
  wants to see it work. Needs a checkout of `themoah/klag`.
- **Docker** — one container against their Kafka.
- **Kubernetes (Helm)** — chart from `https://themoah.github.io/klag`.
- **GitOps** — ArgoCD `Application` / Flux `HelmRelease` written to their config repo, not applied.
- **Jar / native** — no docker.

## 3. Kafka connection

Skip for the Demo POC. Otherwise discover the bootstrap servers rather than asking when you can
(Strimzi CR, `kubectl get svc | grep kafka`, an existing consumer Deployment's env). Confirm the
value with the user either way, then work out the auth from `references/kafka-connect.md`.

Secrets rule: credentials go into a Kubernetes Secret (`kafka.existingSecret`) or `--set` on the
command line. Never write them into a `values.yaml` that sits in a git worktree, and never echo
them back in your output.

Always set `metrics.reporter` / `METRICS_REPORTER` — the default is `none` and produces silence.

## 4. Deploy

Run the commands for the chosen target, one confirmation each. For Helm, write the values file
first, show it, then `helm upgrade --install`. For GitOps, write the manifest, tell the user which
file to commit, and stop — do not `kubectl apply`.

## 5. Verify — do not skip this

Port-forward if needed, then:

```bash
curl -s <klag>/readyz
curl -s <klag>/metrics | grep '^klag_consumer_lag{' | head
```

Report the consumer groups Klag actually discovered, by name and count. This is the point of the
whole command — an install that reports no groups is not a successful install.

If `/readyz` is 503 or the series count is zero, work the failure modes at the end of
`references/kafka-connect.md` before declaring success. Say plainly what is broken.

## 6. Offer MCP

Once metrics flow, offer to turn on the read-only MCP endpoint so the user's agent can query lag
directly: `mcp.enabled=true` plus a generated `mcp.authToken` (`openssl rand -hex 24`) stored in a
Secret, or `-e MCP_ENABLED=true -e MCP_AUTH_TOKEN=...` for docker. If they accept, redeploy and then
tell them to run `/klag:connect`.

## 7. Offer extras — one line each, apply nothing automatically

- `serviceMonitor.enabled=true`, only if the Prometheus Operator CRD exists.
- The Grafana dashboard at `dashboard/demo-dashboard.json` in the repo (Dashboards → Import).
- `metrics.groupFilter` / `metrics.groupExclude` if the cluster has many noisy groups.

## Finish

Summarize in a handful of lines: what runs where, how to reach `/metrics`, which groups are visible,
and the one next step (`/klag:connect`, or wiring the scrape). Docs: `https://klag.dev`.
