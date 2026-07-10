---
title: Native Image
description: Run Klag as a GraalVM native binary for ~70-100 ms startup and ~44 MB RSS, ideal for fast scaling and low-footprint deployments.
---

Klag publishes a GraalVM **native image** alongside the JVM image. It starts in
**~70–100 ms using ~44 MB RSS**, versus ~500 ms / ~119 MB for the JVM image, with the
same config, endpoints, and metrics.

## Run the published image

The native image is tagged `:native` and `:<version>-native`. Replace the example
broker address with one that resolves and is reachable from inside the container; use
Docker network or service DNS for Kafka in another container, or
`host.docker.internal:9092` for Kafka exposed by the host:

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092 \
           -e METRICS_REPORTER=prometheus \
           -p 8888:8888 \
           themoah/klag:native
```

## Build it yourself

Requires a GraalVM JDK 21 (LTS) with `native-image` (e.g.
`sdk install java 21.0.2-graalce`). Run Gradle with that JDK as `JAVA_HOME`/`GRAALVM_HOME`.

```bash
gradle nativeCompile          # -> build/native/nativeCompile/klag (standalone binary)
docker build -f Dockerfile.native -t klag:native .   # distroless runtime image
```

Benchmark startup and memory:

```bash
scripts/benchmark-startup.sh native - build/native/nativeCompile/klag
```

Configuration works like the JVM build. All settings accept environment variables, but
only settings read through Klag's `Env` helper accept runtime `-D` properties. Those
settings support the exact name and a dotted lowercase alias, such as
`-DHTTP_PORT=8881` or `-Dhttp.port=8881`:

```bash
./build/native/nativeCompile/klag -Dhttp.port=8881
```

Reporter integrations, Kafka forwarding, MCP, and other environment-only settings do
not gain `-D` support in a native build. Logging is a separate exception: Logback
accepts exact-name properties such as `-DLOG_LEVEL=DEBUG`, but not dotted aliases such
as `-Dlog.level`. See the
[configuration reference](/configuration/reference/#application) for the full
`Env`-backed list and per-setting notes.

## How it's configured

Native config lives in `build.gradle.kts` (the `graalvmNative` block) plus reachability
hints in `src/main/resources/META-INF/native-image/`. Reflection metadata for Netty,
kafka-clients, logback, and Micrometer comes from the GraalVM Reachability Metadata
Repository (auto-enabled). The entry point is `KlagLauncher` (direct
`new MainVerticle()`, no reflective Vert.x launcher).

:::note
The runtime stays on **JDK 21**. JVM 25 (LTS) showed no startup/memory gain over 21 for
this workload (slightly higher RSS).
:::
