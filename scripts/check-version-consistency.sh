#!/usr/bin/env bash
# Guards against the Helm chart referencing a Docker image tag the release
# pipeline never builds.
#
# release.yml builds `themoah/klag:<build.gradle.kts version>` on the
# `klag-<chart version>` tag that chart-releaser cuts. Chart.yaml `appVersion`
# MUST equal the gradle version (the deployment template defaults image.tag to
# appVersion). When they drifted, a chart was published pointing at a tag that
# was never pushed, and Artifact Hub's security scan failed on that stale
# version. This check fails the build before such a chart is published.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

gradle_ver=$(grep -oE '^version = "[^"]+"' build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')
app_ver=$(grep -oE '^appVersion: "[^"]+"' charts/klag/Chart.yaml | sed -E 's/.*"([^"]+)".*/\1/')

# Empty = grep matched nothing (file moved/reformatted). Without this the later
# "$a" != "$b" checks pass on "" == "" and report a false OK.
for v in gradle_ver app_ver; do
  if [[ -z "${!v}" ]]; then
    echo "::error::Could not extract $v — build.gradle.kts / Chart.yaml format changed?"
    exit 1
  fi
done

if [[ "$app_ver" != "$gradle_ver" ]]; then
  echo "::error::Chart appVersion ($app_ver) != build.gradle.kts version ($gradle_ver)"
  echo "release.yml builds themoah/klag:$gradle_ver; the chart must reference exactly that tag."
  exit 1
fi
echo "Version consistency OK: gradle=$gradle_ver appVersion=$app_ver"
