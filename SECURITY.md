# Security Policy

## Supported versions

Klag follows a rolling security support policy on the latest minor release.

- Application dependencies (Vert.x, Netty, Logback, Micrometer) are kept on the
  latest patch within their supported LTS lines.
- Container images are rebuilt on a current base with security fixes.

## Reporting a vulnerability

Please open a private security advisory on GitHub or email the maintainer listed
in `charts/klag/Chart.yaml`. We will acknowledge within 72 hours.

## Remediation and cadence

- Dependency bumps for disclosed CVEs are prioritized and released promptly.
- Base image refresh: at least monthly, and as-needed for high/critical CVEs.
- CI runs Trivy scans on PRs and `main`. Release pipelines fail on image drift
  between chart and application versions to avoid stale, unscanned tags.

## Image hardening

- JVM image runtime is built on a minimal, non-root base with read-only FS.
- Native image uses `distroless:nonroot` (glibc) with minimal attack surface.

Consumers with stricter policies can also rebuild from source with their own
base image by running `docker build .` or the Helm chart with `image.repository`
and `image.tag` overrides.
