#!/usr/bin/env bash
# Validates the Claude Code plugin shipped from this repo:
#   .claude-plugin/marketplace.json   (marketplace, must sit at the repo root)
#   plugin/                            (the plugin itself — installs copy only this subtree)
# The plugin only loads if the manifests parse and the component dirs sit at the plugin root,
# so this catches the failure modes that would otherwise only show up in a user's client.
set -euo pipefail

cd "$(dirname "$0")/.."
fail=0
err() { echo "FAIL: $*" >&2; fail=1; }

python3 - <<'PY' || fail=1
import json, sys

with open("plugin/.claude-plugin/plugin.json") as f:
    plugin = json.load(f)
with open(".claude-plugin/marketplace.json") as f:
    market = json.load(f)

ok = True
if plugin.get("name") != "klag":
    print(f"FAIL: plugin.json name is {plugin.get('name')!r}, expected 'klag'"
          " (it namespaces the commands: /klag:install)", file=sys.stderr)
    ok = False
# Enumerating these replaces the convention default; we rely on discovery of plugin/commands/.
for key in ("commands", "agents", "hooks", "mcpServers"):
    if key in plugin:
        print(f"FAIL: plugin.json sets {key!r}, which replaces the convention default", file=sys.stderr)
        ok = False
entries = {p.get("name"): p.get("source") for p in market.get("plugins", [])}
if "klag" not in entries:
    print(f"FAIL: marketplace.json lists no 'klag' plugin (found {list(entries)})", file=sys.stderr)
    ok = False
elif entries["klag"] != "./plugin":
    # "./" would make every install copy the whole repo (~600MB with node_modules).
    print(f"FAIL: marketplace source is {entries['klag']!r}, expected './plugin'", file=sys.stderr)
    ok = False
sys.exit(0 if ok else 1)
PY

# Component dirs must be at the plugin root, never inside .claude-plugin/.
for stray in plugin/.claude-plugin/commands plugin/.claude-plugin/skills plugin/.claude-plugin/agents; do
  [ -e "$stray" ] && err "$stray must live at the plugin root, not inside .claude-plugin/"
done

[ -f plugin/skills/klag/SKILL.md ] || err "plugin/skills/klag/SKILL.md missing"

shopt -s nullglob
files=(plugin/commands/*.md plugin/skills/klag/SKILL.md)
[ ${#files[@]} -gt 1 ] || err "no plugin/commands/*.md found"
for f in "${files[@]}"; do
  # Only look between the opening and closing --- : a `description:` in the body does not count.
  awk '
    NR == 1 { if ($0 != "---") exit 1; next }
    /^---[[:space:]]*$/ { closed = 1; exit }
    /^description:[[:space:]]*[^[:space:]]/ { found = 1 }
    END { exit (closed && found) ? 0 : 1 }
  ' "$f" || err "$f: frontmatter missing or has no non-empty description:"
done

# Referenced reference files must exist (a dangling ${CLAUDE_PLUGIN_ROOT} path is a silent no-op).
for ref in $(grep -ohE 'references/[a-z0-9-]+\.md' plugin/commands/*.md plugin/skills/klag/SKILL.md | sort -u); do
  [ -f "plugin/skills/klag/$ref" ] || err "referenced plugin/skills/klag/$ref does not exist"
done

# Deeper schema check, local only — the claude CLI is not on CI runners, so CI gets the
# structural checks above and nothing more. Run this script locally before releasing a plugin
# change if you want the schema validated.
if command -v claude >/dev/null 2>&1; then
  claude plugin validate . >/dev/null || err "claude plugin validate . failed"
  claude plugin validate ./plugin >/dev/null || err "claude plugin validate ./plugin failed"
fi

if [ "$fail" -eq 0 ]; then
  echo "Plugin manifests OK (${#files[@]} component files)."
else
  exit 1
fi
