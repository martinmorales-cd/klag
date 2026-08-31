// Serves the Claude Code plugin's skills from klag.dev and publishes an Agent Skills
// discovery index for them.
//
//   dist/skills/**                              - the skill/command markdown itself
//   dist/.well-known/agent-skills/index.json    - discovery index (agentskills.io v0.2.0)
//   dist/openapi.json                           - re-stamped with the app's Gradle version
//
// The plugin lives at <repo>/plugin and is the single source of truth; this script only
// copies and hashes it, so the served skills cannot drift from the installed ones.
// Run at build time via the "build" npm script, after `astro build`.

import { readdir, readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import matter from 'gray-matter';
import yaml from 'js-yaml';

const yamlEngine = {
  parse: (str) => yaml.load(str) ?? {},
  stringify: (obj) => yaml.dump(obj),
};

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const DEFAULT_PLUGIN = join(dirname(ROOT), 'plugin');
const DEFAULT_OUT = join(ROOT, 'dist');
const SITE = 'https://klag.dev';

async function walk(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await walk(full)));
    else if (entry.name.endsWith('.md')) out.push(full);
  }
  return out;
}

export async function generateSkills({
  pluginDir = DEFAULT_PLUGIN,
  outputDir = DEFAULT_OUT,
} = {}) {
  const files = [
    ...(await walk(join(pluginDir, 'skills'))),
    ...(await walk(join(pluginDir, 'commands'))),
  ].sort();

  const entries = [];
  for (const file of files) {
    const raw = await readFile(file);
    const relPath = relative(pluginDir, file).replace(/\\/g, '/');
    // plugin/skills/klag/SKILL.md -> /skills/klag/SKILL.md
    // plugin/commands/install.md  -> /skills/commands/install.md
    const servePath = relPath.replace(/^skills\//, '');
    const target = join(outputDir, 'skills', servePath);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, raw);

    // Digest over the raw bytes of the artifact as served (agentskills.io v0.2.0).
    const digest = createHash('sha256').update(raw).digest('hex');
    const { data } = matter(raw.toString('utf8'), { engines: { yaml: yamlEngine } });
    const isCommand = relPath.startsWith('commands/');
    const name = data.name
      ?? (isCommand ? `klag:${relPath.replace(/^commands\//, '').replace(/\.md$/, '')}` : null);
    // Reference files under skills/*/references/ are support material the skill links
    // to. Serve them so those links resolve, but keep them out of the index — a name in
    // their frontmatter would otherwise publish one as an installable skill.
    if (!name || /(^|\/)references\//.test(servePath)) continue;

    entries.push({
      name,
      description: data.description ?? '',
      type: 'skill-md',
      url: `${SITE}/skills/${servePath}`,
      digest: `sha256:${digest}`,
      ...(isCommand ? { kind: 'command' } : {}),
    });
  }

  const index = {
    $schema: 'https://schemas.agentskills.io/discovery/0.2.0/schema.json',
    version: '0.2.0',
    name: 'klag',
    description:
      'Agent skills for Klag, the Kafka consumer lag exporter: install it against a ' +
      'cluster, connect its read-only MCP endpoint, and triage consumer lag.',
    homepage: SITE,
    repository: 'https://github.com/themoah/klag',
    license: 'Apache-2.0',
    install: {
      claudeCode: [
        '/plugin marketplace add themoah/klag',
        '/plugin install klag@klag',
      ],
    },
    skills: entries,
  };

  // The published OpenAPI spec describes the application, so its info.version has to
  // track build.gradle.kts rather than being hand-edited on every release.
  const gradle = await readFile(join(dirname(ROOT), 'build.gradle.kts'), 'utf8');
  const appVersion = gradle.match(/^version\s*=\s*"([^"]+)"/m)?.[1];
  const specPath = join(outputDir, 'openapi.json');
  const spec = JSON.parse(await readFile(specPath, 'utf8'));
  if (appVersion && spec.info.version !== appVersion) {
    spec.info.version = appVersion;
    await writeFile(specPath, `${JSON.stringify(spec, null, 2)}\n`, 'utf8');
  }
  console.log(`gen-skills: openapi.json info.version = ${spec.info.version}`);

  const dir = join(outputDir, '.well-known', 'agent-skills');
  await mkdir(dir, { recursive: true });
  await writeFile(join(dir, 'index.json'), `${JSON.stringify(index, null, 2)}\n`, 'utf8');
  console.log(`gen-skills: served ${entries.length} skills + agent-skills/index.json`);
  return index;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  await generateSkills();
}
