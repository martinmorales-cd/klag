import assert from 'node:assert/strict';
import {
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { after, before, test } from 'node:test';

const websiteRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const publicDir = join(websiteRoot, 'public');
const productionDocsDir = join(websiteRoot, 'src', 'content', 'docs');
const generatorPath = join(websiteRoot, 'scripts', 'gen-llms.mjs');
const productClaim =
  /(?:\bKlag\b[^\n]{0,80}\b(?:must-have|best)\b|\b(?:must-have|best)\b[^\n]{0,80}\bKlag\b|\bKlag\b[^\n]{0,80}(?:^|\s)#1\b|(?:^|\s)#1\b[^\n]{0,80}\bKlag\b)/i;
const fixture = [
  '---',
  'title: LLM Generator Fixture',
  'description: Exercises portable Markdown conversion.',
  '---',
  '',
  'import {',
  '  Tabs as PaneSet,',
  '  TabItem as Pane',
  "} from '@astrojs/starlight/components';",
  '',
  '<PaneSet>',
  '  <Pane',
  '    label="Aliased multiline setup"',
  '    icon="seti:settings"',
  '  >',
  '',
  'Keep the uppercase placeholder <Token> intact.',
  '',
  '```text',
  'alpha',
  '',
  '',
  'beta',
  '<Token>',
  ':::note[Literal directive]',
  '```not a closing fence',
  ':::caution[Still literal]',
  '[Literal link](/literal/)',
  '```',
  '',
  '::::caution[Protect this]',
  'Keep this directive content.',
  '::::',
  '',
  '[**Nested label**](',
  '  /guides/nested/',
  '  "Nested title"',
  ')',
  '',
  '![Diagram](/images/diagram.png "Architecture")',
  '[Angle destination](</guides/angle/>)',
  '[Reference guide][guide]',
  '[guide]: /guides/reference/#part "Guide"',
  '[Local anchor](#local)',
  '[External](https://example.com/path)',
  '',
  '  </Pane>',
  '</PaneSet>',
  '',
].join('\n');

let sandboxRoot;
let index;
let full;

before(async () => {
  sandboxRoot = await mkdtemp(join(tmpdir(), 'klag-llms-fixture-'));
  const docsDir = join(sandboxRoot, 'src', 'content', 'docs');
  const outputDir = join(sandboxRoot, 'public');
  await Promise.all([
    mkdir(docsDir, { recursive: true }),
    mkdir(outputDir, { recursive: true }),
  ]);
  await writeFile(join(docsDir, 'fixture.mdx'), fixture, 'utf8');
  const generator = await import(pathToFileURL(generatorPath).href);
  await generator.generateLlms({ docsDir, outputDir });
  [index, full] = await Promise.all([
    readFile(join(outputDir, 'llms.txt'), 'utf8'),
    readFile(join(outputDir, 'llms-full.txt'), 'utf8'),
  ]);
});

after(async () => {
  if (sandboxRoot) await rm(sandboxRoot, { recursive: true, force: true });
});

async function readOptional(path) {
  try {
    return await readFile(path);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

async function restoreIfChanged(path, snapshot) {
  const current = await readOptional(path);
  if (snapshot === null) {
    if (current !== null) await rm(path, { force: true });
  } else if (current === null || !current.equals(snapshot)) {
    await writeFile(path, snapshot);
  }
}

test('llms-full removes MDX syntax while preserving component content', () => {
  assert.doesNotMatch(full, /^(?:import|export)\s/m);
  assert.doesNotMatch(full, /<\/?(?:PaneSet|Pane)\b/);
  assert.match(full, /Keep the uppercase placeholder <Token> intact\./);
});

test('multiline aliased Starlight imports retain semantic tab labels', () => {
  assert.doesNotMatch(full, /Tabs as PaneSet|TabItem as Pane|@astrojs\/starlight/);
  assert.doesNotMatch(full, /<Pane\b|label="Aliased multiline setup"/);
  assert.match(full, /### Aliased multiline setup/);
  assert.match(full, /Keep the uppercase placeholder <Token> intact\./);
});

test('fence-like code lines do not close the surrounding fence', () => {
  assert.match(
    full,
    /```text\nalpha\n\n\nbeta\n<Token>\n:::note\[Literal directive]\n```not a closing fence\n:::caution\[Still literal]\n\[Literal link]\(\/literal\/\)\n```/,
  );
});

test('Starlight directives become portable Markdown callouts', () => {
  assert.deepEqual(
    full.match(/^:{3,}(?:caution|note)(?:\[.*])?$/gm),
    [':::note[Literal directive]', ':::caution[Still literal]'],
  );
  assert.match(full, /> \*\*Caution — Protect this\*\*/);
  assert.match(full, /Keep this directive content\./);
});

test('llms-full makes root-relative Markdown destinations absolute', () => {
  assert.match(full, /!\[Diagram]\(https:\/\/klag\.dev\/images\/diagram\.png/);
  assert.match(full, /\[External]\(https:\/\/example\.com\/path\)/);
  const rootRelative = full.match(/!?\[[^\]\n]*\]\(\/(?!\/)/);
  assert.equal(rootRelative?.[0], '[Literal link](/');
});

test('fixture links cover multiline, nested-label, image, angle, and reference forms', () => {
  assert.match(
    full,
    /\[\*\*Nested label\*\*]\(\n  https:\/\/klag\.dev\/guides\/nested\//,
  );
  assert.match(
    full,
    /!\[Diagram]\(https:\/\/klag\.dev\/images\/diagram\.png "Architecture"\)/,
  );
  assert.match(
    full,
    /\[Angle destination]\(<https:\/\/klag\.dev\/guides\/angle\/>\)/,
  );
  assert.match(
    full,
    /^\[guide]: https:\/\/klag\.dev\/guides\/reference\/#part "Guide"$/m,
  );
  assert.match(full, /\[Local anchor]\(#local\)/);
  assert.match(full, /\[External]\(https:\/\/example\.com\/path\)/);
});

test('parameterized generation leaves production docs and outputs unchanged', async () => {
  const isolatedRoot = await mkdtemp(join(tmpdir(), 'klag-llms-output-'));
  const docsDir = join(isolatedRoot, 'docs');
  const outputDir = join(isolatedRoot, 'output');
  const productionOutputs = [
    join(publicDir, 'llms.txt'),
    join(publicDir, 'llms-full.txt'),
  ];
  const snapshots = await Promise.all(productionOutputs.map(readOptional));

  try {
    await mkdir(docsDir, { recursive: true });
    await writeFile(
      join(docsDir, 'isolated.md'),
      '---\ntitle: Isolated\n---\n\nOnly temporary content.\n',
      'utf8',
    );

    const moduleUrl = `${pathToFileURL(generatorPath).href}?test=${Date.now()}`;
    const generator = await import(moduleUrl);
    assert.equal(typeof generator.generateLlms, 'function');
    await generator.generateLlms({ docsDir, outputDir });

    const isolatedFull = await readFile(
      join(outputDir, 'llms-full.txt'),
      'utf8',
    );
    assert.match(isolatedFull, /# Isolated/);
    assert.match(isolatedFull, /Only temporary content\./);

    const productionAfter = await Promise.all(
      productionOutputs.map(readOptional),
    );
    assert.deepEqual(productionAfter, snapshots);
    const productionDocs = await readdir(
      join(websiteRoot, 'src', 'content', 'docs'),
    );
    assert.ok(!productionDocs.includes('__llms-generator-fixture.mdx'));
  } finally {
    await Promise.all(
      productionOutputs.map((path, index) =>
        restoreIfChanged(path, snapshots[index]),
      ),
    );
    await rm(isolatedRoot, { recursive: true, force: true });
  }
});

test('production corpus generates portable output with critical operational facts', async () => {
  const isolatedRoot = await mkdtemp(join(tmpdir(), 'klag-llms-production-'));
  const outputDir = join(isolatedRoot, 'output');

  try {
    const moduleUrl = `${pathToFileURL(generatorPath).href}?production=${Date.now()}`;
    const generator = await import(moduleUrl);
    const generated = await generator.generateLlms({
      docsDir: productionDocsDir,
      outputDir,
    });
    const generatedFiles = await Promise.all([
      readFile(join(outputDir, 'llms.txt'), 'utf8'),
      readFile(join(outputDir, 'llms-full.txt'), 'utf8'),
    ]);
    assert.deepEqual(generatedFiles, [generated.index, generated.full]);

    assert.doesNotMatch(generated.full, /^(?:import|export)\s/m);
    assert.doesNotMatch(
      generated.full,
      /<\/?(?:Aside|Card|CardGrid|LinkCard|TabItem|Tabs)\b/,
    );
    assert.doesNotMatch(
      generated.full,
      /!?\[[^\]\n]+\]\(\/(?!\/)|^\s{0,3}\[[^\]\n]+]:\s*\/(?!\/)/m,
    );

    assert.doesNotMatch(generated.index, productClaim);
    assert.doesNotMatch(generated.full, productClaim);

    for (const pageUrl of [
      'https://klag.dev/getting-started/quick-start/',
      'https://klag.dev/configuration/reference/',
      'https://klag.dev/integrations/datadog/',
      'https://klag.dev/ai/mcp/',
      'https://klag.dev/guides/troubleshooting/',
    ]) {
      assert.match(generated.index, new RegExp(pageUrl.replaceAll('/', '\\/')));
      assert.match(generated.full, new RegExp(`URL: ${pageUrl.replaceAll('/', '\\/')}`));
    }

    assert.match(generated.index, /KAFKA_BOOTSTRAP_SERVERS=broker\.example\.com:9092/);
    assert.doesNotMatch(
      generated.index,
      /KAFKA_BOOTSTRAP_SERVERS=kafka:9092/,
    );
    assert.match(generated.index, /reachable\s+from inside the Klag container/i);
    assert.match(generated.index, /Docker network or service DNS/i);
    assert.match(generated.index, /host\.docker\.internal/);
    assert.match(generated.full, /`DD_API_KEY` is required/i);
    assert.match(generated.full, /`DD_APP_KEY` is optional/i);
    assert.match(generated.full, /`DD_SITE` defaults to `datadoghq\.com`/i);
    assert.match(generated.full, /three retained transitions/i);
    assert.match(generated.full, /not time-windowed/i);
    assert.match(generated.full, /inspect `recentTransitions`/i);
    assert.match(generated.full, /consecutive state-change count/i);
    assert.match(generated.full, />= 8000/);
    assert.match(generated.full, /at least\s+`TIME_LAG_MIN_MESSAGES`/i);
  } finally {
    await rm(isolatedRoot, { recursive: true, force: true });
  }
});

test('forbidden-superlative matcher catches #1 claims around Klag', () => {
  assert.match('#1 Klag for Kafka monitoring', productClaim);
  assert.match('Klag #1 for Kafka monitoring', productClaim);
});

test('llms.txt leads agents with compact operational facts', () => {
  const docsPosition = index.indexOf('## Docs');
  for (const heading of [
    '## Quick start',
    '## Minimum configuration',
    '## Key Prometheus metrics',
    '## MCP tools',
  ]) {
    const position = index.indexOf(heading);
    assert.ok(position >= 0, `missing ${heading}`);
    assert.ok(position < docsPosition, `${heading} must precede Docs`);
  }

  assert.match(index, /Monitoring consumer progress is essential (?:for|in) production Kafka/);
  assert.match(index, /KAFKA_BOOTSTRAP_SERVERS=broker\.example\.com:9092/);
  assert.match(index, /METRICS_REPORTER=prometheus/);
  assert.match(index, /bare application defaults `METRICS_REPORTER` to `none`/);
  assert.match(index, /Helm chart defaults (?:its reporter|`metrics\.reporter`) to `prometheus`/);
  for (const metric of [
    'klag_consumer_lag',
    'klag_consumer_lag_sum',
    'klag_consumer_lag_max',
    'klag_consumer_lag_min',
    'klag_consumer_lag_velocity',
    'klag_consumer_lag_ms',
    'klag_consumer_commit_staleness_seconds',
    'klag_consumer_lag_retention_percent',
  ]) {
    assert.match(index, new RegExp(`\\b${metric}\\b`));
  }
  assert.match(index, /raw Prometheus gauge is messages\/second × 100/i);
  assert.match(index, /klag_consumer_lag_velocity \/ 100/);
  for (const tool of [
    'list_consumer_groups',
    'get_consumer_group_lag',
    'find_lagging_groups',
    'diagnose',
  ]) {
    assert.match(index, new RegExp(`\\b${tool}\\b`));
  }
  assert.doesNotMatch(index, /\b(?:must-have|best|#1)\b/i);
});
