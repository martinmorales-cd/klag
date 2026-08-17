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
- **Jar / native image** — no docker. The published native build is the `themoah/klag:native`
  image; a standalone binary needs a source build with GraalVM JDK 21.

## 3. Kafka connection

Skip for the Demo POC. Otherwise discover the bootstrap servers rather than asking when you can
(Strimzi CR, `kubectl get svc -A | grep -i kafka`, an existing consumer Deployment's env). Confirm
the value with the user either way, then work out the auth from `references/kafka-connect.md`.

Secrets rule: credentials go into a Kubernetes Secret referenced by `kafka.existingSecret`. That
is the normal path. `--set kafka.saslJaasConfig=...` is a throwaway-POC shortcut only — it leaves
the value in the Helm release and in shell history — so name that trade-off when you use it. Never
write credentials into a `values.yaml` that sits in a git worktree, and never echo them back.

Always set `metrics.reporter` / `METRICS_REPORTER` — the default is `none` and produces silence.

## 4. Deploy

Run the commands for the chosen target, one confirmation each. For Helm, write the values file
first, show it, then `helm upgrade --install`. For GitOps, write the manifest, tell the user which
file to commit, and stop — do not `kubectl apply`.

## 5. Verify — do not skip this

Port-forward if needed, export the base URL as `KLAG_URL`, then:

```bash
curl -s "$KLAG_URL/readyz"
curl -s "$KLAG_URL/metrics" | grep '^klag_consumer_lag{' | head
```

Report the consumer groups Klag actually discovered, by name and count. This is the point of the
whole command — an install that reports no groups is not a successful install.

If `/readyz` is 503 or the series count is zero, work the failure modes at the end of
`references/kafka-connect.md` before declaring success. Say plainly what is broken.

## 6. Offer MCP

Once metrics flow, offer to turn on the read-only MCP endpoint so the user's agent can query lag
directly: `mcp.enabled=true` plus a generated token (`openssl rand -hex 24`) in a Secret referenced
by `mcp.existingSecret` — that is the normal path for both Helm and long-lived Docker. `--set
mcp.authToken=...` and `docker run -e MCP_AUTH_TOKEN=...` are POC exceptions that leave the token
in the Helm release or the container config; say so when you use them. If they accept, redeploy and
tell them to run `/klag:connect`.

## 7. Offer extras — one line each, apply nothing automatically

- `serviceMonitor.enabled=true`, only if the Prometheus Operator CRD exists.
- The Grafana dashboard at `dashboard/demo-dashboard.json` in the repo (Dashboards → Import).
- `metrics.groupFilter` / `metrics.groupExclude` if the cluster has many noisy groups.

## Finish

Summarize in a handful of lines: what runs where, how to reach `/metrics`, which groups are visible,
and the one next step (`/klag:connect`, or wiring the scrape). Docs: `https://klag.dev`.
