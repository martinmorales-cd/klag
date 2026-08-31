import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
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

// Reference files are support material a skill links to. They must still be served so
// those links resolve, but an agent reading the index must not be offered one as an
// installable skill. The real references carry no frontmatter, so this builds a fixture
// where one does -- that is the case the name check alone would let through.
test('reference files are served but kept out of the discovery index', async () => {
  const fixtureDir = await mkdtemp(join(tmpdir(), 'klag-plugin-'));
  const outputDir = await mkdtemp(join(tmpdir(), 'klag-skills-'));

  try {
    await mkdir(join(fixtureDir, 'skills', 'klag', 'references'), { recursive: true });
    // generateSkills walks both trees; the real plugin always has commands/.
    await mkdir(join(fixtureDir, 'commands'), { recursive: true });
    await writeFile(
      join(fixtureDir, 'skills', 'klag', 'SKILL.md'),
      '---\nname: klag\ndescription: Set up Klag.\n---\n\nBody.\n',
    );
    await writeFile(
      join(fixtureDir, 'skills', 'klag', 'references', 'deploy-targets.md'),
      '---\nname: deploy-targets\ndescription: Support material.\n---\n\nBody.\n',
    );
    await writeFile(join(outputDir, 'openapi.json'), JSON.stringify({ info: { version: '0' } }));

    const index = await generateSkills({ pluginDir: fixtureDir, outputDir });

    assert.deepEqual(index.skills.map((skill) => skill.name), ['klag']);
    // Served, so the skill's own links to it still resolve.
    await readFile(join(outputDir, 'skills', 'klag', 'references', 'deploy-targets.md'));
  } finally {
    await rm(fixtureDir, { recursive: true, force: true });
    await rm(outputDir, { recursive: true, force: true });
  }
});
