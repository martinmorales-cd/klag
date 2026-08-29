import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

import { generateSkills } from './gen-skills.mjs';

const websiteRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const pluginDir = join(dirname(websiteRoot), 'plugin');

// A digest that does not match the served bytes is worse than no index at all: an agent
// that verifies the artifact refuses to install it.
test('agent-skills index digests match the served skill files', async () => {
  const outputDir = await mkdtemp(join(tmpdir(), 'klag-skills-'));

  try {
    // openapi.json is re-stamped in place, so the fixture needs a copy to work on.
    const spec = JSON.parse(await readFile(join(websiteRoot, 'public', 'openapi.json'), 'utf8'));
    await writeFile(join(outputDir, 'openapi.json'), JSON.stringify(spec));

    const index = await generateSkills({ pluginDir, outputDir });

    assert.equal(index.$schema, 'https://schemas.agentskills.io/discovery/0.2.0/schema.json');
    assert.ok(index.skills.length >= 4, 'expected the skill plus the three commands');

    for (const skill of index.skills) {
      assert.ok(skill.name, 'skill entry has no name');
      assert.ok(skill.description, `${skill.name} has no description`);
      assert.equal(skill.type, 'skill-md');
      assert.match(skill.digest, /^sha256:[0-9a-f]{64}$/);

      const served = join(outputDir, new URL(skill.url).pathname.replace(/^\//, ''));
      const bytes = await readFile(served);
      assert.equal(
        skill.digest,
        `sha256:${createHash('sha256').update(bytes).digest('hex')}`,
        `${skill.name}: digest does not match the file served at ${skill.url}`,
      );
    }

    // The published spec must carry the application's Gradle version, not a stale literal.
    const gradle = await readFile(join(dirname(websiteRoot), 'build.gradle.kts'), 'utf8');
    const appVersion = gradle.match(/^version\s*=\s*"([^"]+)"/m)?.[1];
    const published = JSON.parse(await readFile(join(outputDir, 'openapi.json'), 'utf8'));
    assert.equal(published.info.version, appVersion);
  } finally {
    await rm(outputDir, { recursive: true, force: true });
  }
});
