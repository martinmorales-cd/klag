In any order:
* (WIP) Filter out topics/consumer groups.
* ~~Consumer group state tacking (Stable, Rebalancing, Dead, Empty).~~
* ~~Lag velocity (increasing or decreasing over window of time)~~.
* Estimated Time to Catch Up (or Fall Behind).
* Per-Member Assignment Tracking / Partitions per member of consumer group.
* Chunking request to kafka.
* ~~Hot Partition Detection - Flag partitions with disproportionate lag.~~
* Track rebalance frequency—too many rebalances indicate instability.
* ~~Java 21 and usage of virtual threads.~~
* Enable virtual threads by default after benchmarking and compatibility validation.
* Convert lag to estimate of seconds.
* ~~Dockerfile~~ + Helm chart.
* ~~Github actions~~.
* ~~Grafana dashboard.~~
* Run with 2 or metrics reporter (e.g. prometheus and datadog)

* sinks:
  * ~~OTel~~
  * Prom push gateway
  * statsD / DogStatsD.
  * Google stackdriver.
  * CloudWatch.


Internal tasks:
* Collect all consts and default values into single AppConfig.
* Local integration stack with k3s/minikube/docker compose.
* Shrink the agent-plugin install footprint. `source: "./plugin"` already cut the installed
  payload from 620 MB (local dir install of the whole repo — node_modules, build/, .env) to
  36 KB, but a `/plugin marketplace add themoah/klag` still clones the full repo into
  `~/.claude/plugins/`. Measure it once published; if it is large, look at a shallow/sparse
  clone, `.gitattributes export-ignore`, or splitting the marketplace into its own repo.
