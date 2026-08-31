---
title: Privacy
description: What klag.dev collects (almost nothing) and what the Klag software itself sends (nothing — it has no telemetry and phones home nowhere).
---

Two separate things are worth separating: this website, and the software you run.

## The software: no telemetry

**Klag has no telemetry.** It does not phone home, does not check for updates, does not send
usage statistics, and does not contact any endpoint the operator has not configured. There is
no vendor account and nothing to opt out of.

The only network connections a Klag instance makes are:

1. **To your Kafka brokers**, over the AdminClient protocol, using the credentials you supply.
   Read-only: it describes consumer groups and topics. It never produces, consumes, or commits.
2. **To the metrics backend you configure.** With `METRICS_REPORTER=prometheus`, Klag makes no
   outbound metrics connection at all — it serves `/metrics` and waits to be scraped. With
   `datadog` or `otlp`, it pushes to the endpoint you set, and nowhere else.

The data in those metrics is consumer group names, topic names, partition numbers, offsets,
and — when `CONSUMER_MEMBER_LABELS_ENABLED` is on — consumer member hosts and client IDs.
Message payloads are never read, because Klag never consumes messages. If group or topic names
are sensitive in your environment, `METRICS_GROUP_FILTER` and `METRICS_GROUP_EXCLUDE` control
what is collected at the source.

The opt-in MCP endpoint (`MCP_ENABLED=true`) serves the same in-memory snapshot over HTTP.
It is read-only, it is off by default, and `MCP_AUTH_TOKEN` puts a bearer token in front of
it. Expose it deliberately.

## This website

klag.dev is a static documentation site on Cloudflare. It sets no cookies, has no login, no
comments, no ad network, and no third-party trackers or embedded scripts beyond what is
described here.

- **Analytics.** Cloudflare Web Analytics, which is cookieless and does not fingerprint
  visitors. It reports aggregate page views, referrers, and country — no cross-site tracking
  and no per-visitor profile.
- **Server logs.** Cloudflare processes requests and retains standard edge logs (IP address,
  user agent, requested path) for a short period, as any web host does. That handling is
  covered by [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).
- **No forms.** The site collects no email addresses and has no newsletter, so there is
  nothing to unsubscribe from. Contact happens on GitHub, under GitHub's own policies.

## The documentation MCP server

`https://klag.dev/mcp` answers questions about the documentation. It is unauthenticated and
read-only, it stores nothing, and it has no access to any Kafka cluster — it reads only the
published documentation corpus. Queries sent to it are handled at the edge and are subject to
the same Cloudflare logging as any other request.

## Changes and contact

Material changes to this page are visible in the site's
[git history](https://github.com/themoah/klag/commits/main/website). Questions about privacy
or data handling: [open an issue](https://github.com/themoah/klag/issues).
