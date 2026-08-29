---
title: About Klag
description: What Klag is, who maintains it, how it is licensed, and why it exists as an alternative to the unmaintained kafka-lag-exporter.
---

Klag is an open-source **Kafka consumer lag exporter**. It watches the consumer groups on
a Kafka cluster and turns their progress into metrics your existing observability stack can
alert on: Prometheus, Datadog, or any OTLP endpoint.

It is a single small service. You run it next to your cluster, point it at your brokers with
read-only credentials, and it does nothing else — it never produces, consumes, or commits
offsets, and it has no UI, no database, and no control plane.

## Why it exists

The tool most teams reached for, [kafka-lag-exporter][kle], stopped being maintained. Its
Scala/Akka stack also carried a licensing change and a heavy runtime for what is, in the end,
a periodic AdminClient poll. Klag was written to be the boring replacement: same job, current
dependencies, [equivalent metric names](/getting-started/migrating-from-kafka-lag-exporter/)
so dashboards and alerts survive the swap.

Along the way it grew the signals that a raw lag number does not give you:

- **Lag velocity** — whether the gap is growing or shrinking, so you can tell a backlog that
  is recovering from one that is not.
- **Time-based lag** — how far behind a group is in *seconds*, which is what an SLA is
  usually written against.
- **Commit staleness** — a group holding lag while its committed offset stops moving. That is
  a stuck consumer, and a flat lag value hides it.
- **Hot partitions** — one partition carrying statistically more than its peers, usually a
  partition-key problem rather than a consumer problem.
- **Retention risk** — how much of the retention window the lag has eaten, so you get warned
  before Kafka deletes data the consumer has not read.
- **Under-replicated partitions** — fault-tolerance loss, from metadata Klag already fetches.

## Technical shape

Java 21 on [Vert.x](https://vertx.io/) with [Micrometer](https://micrometer.io/) for metrics.
Collection is deliberately batched: request volume against the brokers stays independent of
how many topics exist, so adding topics does not add load. It ships as a container image, a
Helm chart, a fat JAR, and a GraalVM native image that starts in about 70-100 ms and holds
about 44 MB of RSS.

## Maintainers, license, support

Klag is maintained by [themoah](https://github.com/themoah) and released under the
**Apache License 2.0**. It is free, with no hosted tier, no account, and no paid features —
see [pricing](https://klag.dev/pricing.md).

- **Source:** [github.com/themoah/klag](https://github.com/themoah/klag)
- **Bugs, questions, feature requests:** [GitHub issues](https://github.com/themoah/klag/issues)
- **Contributing:** [Contributing guide](/development/contributing/)
- **Container image:** [`themoah/klag`](https://hub.docker.com/r/themoah/klag)
- **Helm chart:** [Artifact Hub](https://artifacthub.io/packages/helm/klag/klag)

Support is community support, through GitHub issues. There is no commercial support contract
and no SLA; if you need one, the license lets you run and modify Klag however you like.

## For AI agents

The documentation is published in machine-readable form at [/llms.txt](https://klag.dev/llms.txt)
and [/llms-full.txt](https://klag.dev/llms-full.txt), every page has a markdown twin (append
`.md` to any URL), and klag.dev hosts a read-only documentation MCP server. See
[Developers](/developers/) and [Agent setup](/ai/agent-setup/).

[kle]: https://github.com/seglo/kafka-lag-exporter
