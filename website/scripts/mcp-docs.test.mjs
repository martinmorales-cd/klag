// Covers the branching parts of the klag.dev docs MCP server: tool dispatch, search
// ranking, path resolution, config/metric lookup, and Accept negotiation. The Worker
// itself (src/worker.ts) is JSON-RPC framing over these functions.
//
// Requires src/generated/docs.json — `npm run build` or `node scripts/gen-llms.mjs`.

import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  TOOLS,
  RESOURCES,
  callTool,
  resolveDocPath,
  prefersMarkdown,
} from '../src/mcp-docs.mjs';

const textOf = (result) => {
  assert.ok(!('error' in result), `unexpected tool error: ${result.error}`);
  return result.content[0].text;
};

test('every tool is describable and callable by an LLM', () => {
  const names = TOOLS.map((tool) => tool.name);
  assert.deepEqual(names, [
    'search_klag_docs',
    'get_klag_doc',
    'get_klag_config',
    'get_klag_metric',
  ]);
  assert.equal(new Set(names).size, names.length, 'tool names must be unique');

  for (const tool of TOOLS) {
    assert.match(tool.name, /^[a-z][a-z0-9_]*$/, `${tool.name}: unconventional tool name`);
    assert.ok(tool.description.length > 80, `${tool.name}: description too thin for tool choice`);
    assert.equal(tool.inputSchema.type, 'object');
    assert.equal(tool.inputSchema.additionalProperties, false);
    assert.equal(tool.annotations.readOnlyHint, true);
    for (const [param, schema] of Object.entries(tool.inputSchema.properties)) {
      assert.ok(schema.type, `${tool.name}.${param}: no type`);
      assert.ok(schema.description, `${tool.name}.${param}: no description`);
    }
  }

  for (const resource of RESOURCES) {
    assert.match(resource.uri, /^https:\/\/klag\.dev\//);
    assert.equal(resource.mimeType, 'text/markdown');
  }
});

test('search ranks the page that owns the topic first', () => {
  const result = callTool('search_klag_docs', { query: 'lag velocity', limit: 3 });
  const text = textOf(result);
  assert.match(text, /^## Lag Velocity\nURL: https:\/\/klag\.dev\/metrics\/lag-velocity\//);

  const stuck = textOf(callTool('search_klag_docs', { query: 'stuck consumer', limit: 1 }));
  assert.match(stuck, /detect-stuck-consumers/);

  // A miss is a usable answer, not an error: it points at the full index.
  const miss = textOf(callTool('search_klag_docs', { query: 'zzzznotathing' }));
  assert.match(miss, /llms\.txt/);

  assert.ok('error' in callTool('search_klag_docs', { query: '   ' }), 'blank query must fail');
  assert.ok('error' in callTool('search_klag_docs', {}), 'missing query must fail');
});

test('search honours the limit, clamped to the schema range', () => {
  const count = (text) => text.split('\n---\n').length;
  assert.equal(count(textOf(callTool('search_klag_docs', { query: 'kafka', limit: 2 }))), 2);
  // Out-of-range and junk limits fall back to sane values rather than throwing.
  assert.ok(count(textOf(callTool('search_klag_docs', { query: 'kafka', limit: 0 }))) >= 1);
  assert.ok(count(textOf(callTool('search_klag_docs', { query: 'kafka', limit: 999 }))) <= 20);
});

test('doc paths resolve however an agent spells them', () => {
  for (const spelling of [
    '/metrics/lag-velocity/',
    'metrics/lag-velocity',
    '/metrics/lag-velocity',
    '/metrics/lag-velocity.md',
    'https://klag.dev/metrics/lag-velocity/',
  ]) {
    assert.equal(resolveDocPath(spelling), '/metrics/lag-velocity/', `failed for ${spelling}`);
  }

  // Homepage: agents guess all of these.
  for (const home of ['/', '', 'index', 'index.md', '/index/', 'home']) {
    assert.equal(resolveDocPath(home), '/', `failed for ${JSON.stringify(home)}`);
  }

  assert.equal(resolveDocPath('/no/such/page/'), null);
});

test('get_klag_doc returns a page, a listing, or a usable error', () => {
  const page = textOf(callTool('get_klag_doc', { path: 'index' }));
  assert.match(page, /^# Klag: Kafka Consumer Lag Exporter\nURL: https:\/\/klag\.dev\//);

  const listing = textOf(callTool('get_klag_doc', {}));
  assert.match(listing, /^- \/ — /m);
  assert.match(listing, /^- \/metrics\/lag-velocity\/ — Lag Velocity$/m);

  const missing = callTool('get_klag_doc', { path: '/nope/' });
  assert.ok('error' in missing);
  assert.match(missing.error, /list every page/);
});

test('config lookup answers by exact key, by prefix, and by wording', () => {
  const exact = textOf(callTool('get_klag_config', { name: 'KAFKA_CHUNK_COUNT' }));
  assert.match(exact, /^KAFKA_CHUNK_COUNT\n {2}default: .*1/);
  assert.match(exact, /docs: https:\/\/klag\.dev\/configuration\//);

  const prefix = textOf(callTool('get_klag_config', { name: 'HOT_PARTITION_' }));
  assert.ok(prefix.split('\n\n').length >= 4, 'prefix search should return the whole family');

  assert.ok(textOf(callTool('get_klag_config', {})).includes('KAFKA_BOOTSTRAP_SERVERS'));
  assert.ok('error' in callTool('get_klag_config', { name: 'NOT_A_KLAG_SETTING' }));
});

test('metric lookup accepts both the dotted and the Prometheus spelling', () => {
  const dotted = textOf(callTool('get_klag_metric', { name: 'klag.consumer.lag.velocity' }));
  const prometheus = textOf(callTool('get_klag_metric', { name: 'klag_consumer_lag_velocity' }));
  assert.match(dotted, /klag\.consumer\.lag\.velocity/);
  assert.match(prometheus, /klag\.consumer\.lag\.velocity/);

  // Mixed separators: the docs spell this one with both a dot and an underscore.
  const staleness = textOf(
    callTool('get_klag_metric', { name: 'klag_consumer_commit_staleness_seconds' }),
  );
  assert.match(staleness, /klag\.consumer\.commit\.staleness_seconds/);

  assert.ok('error' in callTool('get_klag_metric', { name: 'kafka_lag_exporter_total' }));
});

test('unknown tools fail as tool errors, not crashes', () => {
  const result = callTool('drop_consumer_group', { group: 'orders' });
  assert.ok('error' in result);
  assert.match(result.error, /Unknown tool/);
});

test('markdown negotiation respects q-values', () => {
  assert.equal(prefersMarkdown('text/markdown'), true);
  assert.equal(prefersMarkdown('text/markdown, text/html;q=0.5'), true);
  assert.equal(prefersMarkdown('text/html, text/markdown'), true);
  // A browser that merely tolerates markdown must still get HTML.
  assert.equal(prefersMarkdown('text/html, text/markdown;q=0.1'), false);
  assert.equal(prefersMarkdown('text/markdown;q=0'), false);
  assert.equal(prefersMarkdown('text/html,application/xhtml+xml,*/*;q=0.8'), false);
  assert.equal(prefersMarkdown(null), false);
  assert.equal(prefersMarkdown(''), false);
});

test('markdown negotiation resolves wildcard ranges by specificity', () => {
  // HTML matches */* at 0.8 here, so this header asks for HTML despite naming markdown.
  assert.equal(prefersMarkdown('text/markdown;q=0.5, */*;q=0.8'), false);
  assert.equal(prefersMarkdown('text/markdown;q=0.5, text/*;q=0.8'), false);
  // Ties go to markdown: the caller named it explicitly.
  assert.equal(prefersMarkdown('text/markdown;q=0.8, */*;q=0.8'), true);
  assert.equal(prefersMarkdown('text/markdown, */*;q=0.8'), true);
  // An exact text/html range wins over a looser one regardless of q ordering.
  assert.equal(prefersMarkdown('text/markdown;q=0.9, text/html;q=0.2, */*'), true);
  assert.equal(prefersMarkdown('text/markdown;q=0.2, text/html;q=0.9, */*;q=0.1'), false);
  // A bare wildcard expresses no markdown preference at all.
  assert.equal(prefersMarkdown('*/*'), false);
});
